package com.llll.rag.service.serviceImpl;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llll.rag.ingestion.embedding.service.EmbeddingService;
import com.llll.rag.search.exception.RetrievalUnavailableException;
import com.llll.rag.search.model.RecallChannelResult;
import com.llll.rag.search.model.RecallChannelResult.Channel;
import com.llll.rag.search.model.RecallChannelResult.FailureStage;
import com.llll.rag.search.model.RecallChannelResult.Status;
import com.llll.rag.search.model.RetrieveResult;
import com.llll.rag.search.properties.RagSearchProperties;
import com.llll.rag.search.properties.RerankProperties;
import com.llll.rag.search.request.RagSearchRequest;
import com.llll.rag.search.retriever.KeywordRetriever;
import com.llll.rag.search.retriever.VectorRetriever;
import com.llll.rag.service.RagService;
import com.llll.rag.knowledge.dao.mapper.RagSearchMapper;
import jakarta.annotation.PreDestroy;
import jakarta.json.JsonException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class RagServiceImpl implements RagService, AutoCloseable {

    private final EmbeddingService embeddingService;
    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final RagSearchProperties searchProperties;
    private final RerankProperties rerankProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient;
    private final RagSearchMapper ragSearchMapper;
    private final ThreadPoolTaskExecutor vectorRecallExecutor;
    private final ThreadPoolTaskExecutor keywordRecallExecutor;


    public RagServiceImpl(
            EmbeddingService embeddingService,
            VectorRetriever vectorRetriever,
            KeywordRetriever keywordRetriever,
            RagSearchProperties searchProperties,
            RerankProperties rerankProperties,
            RagSearchMapper ragSearchMapper,
            @Qualifier("vectorRecallExecutor") ThreadPoolTaskExecutor vectorRecallExecutor,
            @Qualifier("keywordRecallExecutor") ThreadPoolTaskExecutor keywordRecallExecutor) {
        this.embeddingService = embeddingService;
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.searchProperties = searchProperties;
        this.rerankProperties = rerankProperties;
        this.ragSearchMapper = ragSearchMapper;
        this.vectorRecallExecutor = vectorRecallExecutor;
        this.keywordRecallExecutor = keywordRecallExecutor;
        this.httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(rerankProperties.getConnectTimeout().toMillis()))
                                .setSocketTimeout(Timeout.ofMilliseconds(rerankProperties.getRequestTimeout().toMillis()))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(rerankProperties.getConnectTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(rerankProperties.getRequestTimeout().toMillis()))
                        .setRedirectsEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .build();
    }


    @Override
    public List<RetrieveResult> search(RagSearchRequest request) {
        validateRequest(request);
        requireSearchNotInterrupted();
        String query = request.getQuery();
        long startedNanos = System.nanoTime();
        long vectorBudgetNanos = Math.min(
                searchProperties.getVectorTimeout().toNanos(), searchProperties.getRecallTimeout().toNanos());
        long keywordBudgetNanos = Math.min(
                searchProperties.getKeywordTimeout().toNanos(), searchProperties.getRecallTimeout().toNanos());

        Future<RecallChannelResult> vectorFuture = null;
        Future<RecallChannelResult> keywordFuture = null;
        RecallChannelResult vectorResult;
        RecallChannelResult keywordResult;
        try {
            // 两路先提交再等待；计时从提交前开始，排队也占用召回预算。
            vectorFuture = submitRecall(query, Channel.VECTOR, vectorRecallExecutor, startedNanos, vectorBudgetNanos);
            keywordFuture = submitRecall(query, Channel.KEYWORD, keywordRecallExecutor, startedNanos, keywordBudgetNanos);
            // 先收集截止时间更早的一路，及时取消它，不延长其等待预算。
            if (keywordBudgetNanos <= vectorBudgetNanos) {
                keywordResult = awaitRecall(keywordFuture, Channel.KEYWORD, startedNanos, keywordBudgetNanos);
                vectorResult = awaitRecall(vectorFuture, Channel.VECTOR, startedNanos, vectorBudgetNanos);
            } else {
                vectorResult = awaitRecall(vectorFuture, Channel.VECTOR, startedNanos, vectorBudgetNanos);
                keywordResult = awaitRecall(keywordFuture, Channel.KEYWORD, startedNanos, keywordBudgetNanos);
            }
        } finally {
            cancelRecall(vectorFuture, vectorRecallExecutor);
            cancelRecall(keywordFuture, keywordRecallExecutor);
        }
        requireSearchNotInterrupted();
        logRecallResult(vectorResult);
        logRecallResult(keywordResult);

        boolean degraded = vectorResult.getStatus() != Status.SUCCESS || keywordResult.getStatus() != Status.SUCCESS;
        List<RetrieveResult> fusedResults = rrf(
                vectorResult.getCandidates(), keywordResult.getCandidates(), searchProperties.getFusionTopK());
        if (fusedResults.isEmpty()) {
            if (degraded) {
                // 两路都失败，或一路失败而另一路未命中，都不能向模型宣称“没有资料”。
                throw new RetrievalUnavailableException();
            }
            return new ArrayList<>();
        }

        // 不再强制回查 PG，避免 PG 故障中断已经成功的 ES 分支。
        List<RetrieveResult> rankedResults = rerankOrKeepFusion(query, fusedResults);
        requireSearchNotInterrupted();
        int resultCount = Math.min(searchProperties.getFinalTopK(), rankedResults.size());
        return new ArrayList<>(rankedResults.subList(0, resultCount));
    }

    private Future<RecallChannelResult> submitRecall(
            String query, Channel channel, ThreadPoolTaskExecutor executor, long startedNanos, long budgetNanos) {
        try {
            return executor.submit(() -> recallChannel(query, channel, startedNanos, budgetNanos));
        } catch (RejectedExecutionException exception) {
            RecallChannelResult result = buildRecallResult(channel, Status.FAILED, List.of(),
                    FailureStage.SUBMISSION, exception.getClass().getSimpleName(), startedNanos);
            return CompletableFuture.completedFuture(result);
        }
    }

    private RecallChannelResult recallChannel(String query, Channel channel, long startedNanos, long budgetNanos) {
        requireSearchNotInterrupted();
        if (System.nanoTime() - startedNanos >= budgetNanos) {
            return buildRecallResult(channel, Status.TIMEOUT, List.of(), FailureStage.WAITING, null, startedNanos);
        }
        FailureStage stage = FailureStage.ES;
        try {
            List<RetrieveResult> candidates;
            if (channel == Channel.VECTOR) {
                stage = FailureStage.EMBEDDING;
                List<float[]> vectors = embeddingService.embedBatch(List.of(query));
                if (vectors == null || vectors.size() != 1) {
                    throw new IllegalStateException("单问题向量化必须返回一个向量");
                }
                requireSearchNotInterrupted();
                if (System.nanoTime() - startedNanos >= budgetNanos) {
                    return buildRecallResult(channel, Status.TIMEOUT, List.of(), stage, null, startedNanos);
                }
                stage = FailureStage.PG_VECTOR;
                candidates = vectorRetriever.retrieve(vectors.getFirst(), searchProperties.getVectorTopK());
            } else {
                candidates = keywordRetriever.retrieve(query, searchProperties.getBm25TopK());
            }
            requireSearchNotInterrupted();
            if (candidates == null) {
                throw new IllegalStateException("召回器返回了 null 结果");
            }
            // 在分支内核对结果，避免异常候选进入融合后拖垮另一条成功分支。
            for (RetrieveResult candidate : candidates) {
                if (candidate == null || !StringUtils.hasText(candidate.getId())
                        || !StringUtils.hasText(candidate.getContent())) {
                    throw new IllegalStateException("召回候选缺少 Chunk ID 或正文");
                }
                Integer rank = candidate.getBm25Rank();
                if (channel == Channel.VECTOR) {
                    rank = candidate.getVectorRank();
                }
                if (rank == null || rank < 1) {
                    throw new IllegalStateException("召回候选缺少合法排名");
                }
            }
            return buildRecallResult(channel, Status.SUCCESS, candidates, null, null, startedNanos);
        } catch (IllegalStateException | DataAccessException | ElasticsearchException | JsonException exception) {
            requireSearchNotInterrupted();
            return buildRecallResult(channel, Status.FAILED, List.of(), stage,
                    exception.getClass().getSimpleName(), startedNanos);
        }
    }

    private RecallChannelResult awaitRecall(
            Future<RecallChannelResult> future, Channel channel, long startedNanos, long budgetNanos) {
        requireSearchNotInterrupted();
        long remainingNanos = Math.max(0L, budgetNanos - (System.nanoTime() - startedNanos));
        try {
            RecallChannelResult result = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            // 另一条分支可能等待更久，不能把已超出本路期限的迟到结果当成成功。
            if (result.getElapsedNanos() > budgetNanos) {
                return buildRecallResult(channel, Status.TIMEOUT, List.of(), FailureStage.WAITING, null, startedNanos);
            }
            return result;
        } catch (TimeoutException exception) {
            future.cancel(true);
            return buildRecallResult(channel, Status.TIMEOUT, List.of(), FailureStage.WAITING, null, startedNanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("检索已中断");
        } catch (ExecutionException exception) {
            // 依赖故障已在分支内转换；编程错误和取消不伪装成普通降级。
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("召回任务异常结束", cause);
        }
    }

    private RecallChannelResult buildRecallResult(
            Channel channel, Status status, List<RetrieveResult> candidates,
            FailureStage failureStage, String failureType, long startedNanos) {
        return RecallChannelResult.builder()
                .channel(channel)
                .status(status)
                .candidates(List.copyOf(candidates))
                .failureStage(failureStage)
                .failureType(failureType)
                .elapsedNanos(System.nanoTime() - startedNanos)
                .build();
    }

    private void cancelRecall(Future<RecallChannelResult> future, ThreadPoolTaskExecutor executor) {
        if (future != null) {
            if (!future.isDone()) {
                future.cancel(true);
            }
            // 移除尚未开始的已取消任务，避免它们继续占用有界队列。
            executor.getThreadPoolExecutor().purge();
        }
    }

    private void logRecallResult(RecallChannelResult result) {
        if (result.getStatus() != Status.SUCCESS) {
            log.warn("召回通道降级，channel={}，status={}，stage={}，failureType={}，elapsedMs={}",
                    result.getChannel(), result.getStatus(), result.getFailureStage(),
                    result.getFailureType(), result.getElapsedMillis());
        } else {
            log.debug("召回通道完成，channel={}，candidateCount={}，elapsedMs={}",
                    result.getChannel(), result.getCandidates().size(), result.getElapsedMillis());
        }
    }

    private List<RetrieveResult> rerankOrKeepFusion(String query, List<RetrieveResult> fusedResults) {
        requireSearchNotInterrupted();
        try {
            return rerank(query, fusedResults);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            requireSearchNotInterrupted();
            log.warn("重排不可用，保留 RRF 顺序，failureType={}", exception.getClass().getSimpleName());
            return fusedResults;
        }
    }

    private void requireSearchNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("检索已中断");
        }
    }

    private void validateRequest(RagSearchRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            throw new IllegalArgumentException("搜索问题不能为空");
        }
        String query = request.getQuery();
        if (query.codePointCount(0, query.length()) > searchProperties.getMaxQueryLength()) {
            throw new IllegalArgumentException("搜索问题超过配置的最大字符数");
        }
    }

    private List<RetrieveResult> reloadActiveCandidates(List<RetrieveResult> candidates) {
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> chunkIds = new ArrayList<>();
        for (RetrieveResult candidate : candidates) {
            chunkIds.add(candidate.getId());
        }
        List<RetrieveResult> currentChunks = ragSearchMapper.selectActiveCandidates(chunkIds);
        Map<String, String> currentContent = new HashMap<>();
        for (RetrieveResult currentChunk : currentChunks) {
            currentContent.put(currentChunk.getId(), currentChunk.getContent());
        }
        List<RetrieveResult> activeCandidates = new ArrayList<>();
        for (RetrieveResult candidate : candidates) {
            String content = currentContent.get(candidate.getId());
            if (StringUtils.hasText(content)) {
                activeCandidates.add(RetrieveResult.builder()
                        .id(candidate.getId())
                        .content(content)
                        .vectorRank(candidate.getVectorRank())
                        .bm25Rank(candidate.getBm25Rank())
                        .rrfScore(candidate.getRrfScore())
                        .rerankScore(candidate.getRerankScore())
                        .build());
            }
        }
        return activeCandidates;
    }

    @Override
    public List<RetrieveResult> rrf(
            List<RetrieveResult> vectorResults,
            List<RetrieveResult> keywordResults,
            int topK) {
        if (vectorResults == null || keywordResults == null || topK < 1) {
            throw new IllegalArgumentException("RRF 候选列表不能为 null，保留数量必须为正数");
        }
        Map<String, RetrieveResult> mergedResults = new HashMap<>();
        for (RetrieveResult vectorResult : vectorResults) {
            validateCandidate(vectorResult);
            validateRank(vectorResult.getVectorRank());
            RetrieveResult merged = getOrCreateCandidate(mergedResults, vectorResult);
            if (merged.getVectorRank() == null || vectorResult.getVectorRank() < merged.getVectorRank()) {
                merged.setVectorRank(vectorResult.getVectorRank());
            }
        }
        for (RetrieveResult keywordResult : keywordResults) {
            validateCandidate(keywordResult);
            validateRank(keywordResult.getBm25Rank());
            RetrieveResult merged = getOrCreateCandidate(mergedResults, keywordResult);
            if (merged.getBm25Rank() == null || keywordResult.getBm25Rank() < merged.getBm25Rank()) {
                merged.setBm25Rank(keywordResult.getBm25Rank());
            }
        }

        List<RetrieveResult> fusedResults = new ArrayList<>(mergedResults.values());
        for (RetrieveResult result : fusedResults) {
            double score = 0.0;
            if (result.getVectorRank() != null) {
                score += 1.0 / ((double) searchProperties.getRrfK() + result.getVectorRank());
            }
            if (result.getBm25Rank() != null) {
                score += 1.0 / ((double) searchProperties.getRrfK() + result.getBm25Rank());
            }
            result.setRrfScore(score);
        }
        fusedResults.sort(Comparator
                .comparing(RetrieveResult::getRrfScore, Comparator.reverseOrder())
                .thenComparing(RetrieveResult::getId));
        int resultCount = Math.min(topK, fusedResults.size());
        return new ArrayList<>(fusedResults.subList(0, resultCount));
    }

    /** 不修改召回器持有的结果。同 ID 两路均命中时，沿用先合并的 PG 正文。 */
    private RetrieveResult getOrCreateCandidate(
            Map<String, RetrieveResult> mergedResults,
            RetrieveResult source) {
        RetrieveResult merged = mergedResults.get(source.getId());
        if (merged == null) {
            merged = RetrieveResult.builder()
                    .id(source.getId())
                    .content(source.getContent())
                    .build();
            mergedResults.put(merged.getId(), merged);
        }
        return merged;
    }

    @Override
    public List<RetrieveResult> rerank(String query, List<RetrieveResult> candidates) {
        if (!StringUtils.hasText(query) || candidates == null) {
            throw new IllegalArgumentException("重排问题不能为空，候选列表不能为 null");
        }
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        if (candidates.size() > 500) {
            throw new IllegalArgumentException("百炼文本重排单次候选数量不能超过 500");
        }
        for (RetrieveResult candidate : candidates) {
            validateCandidate(candidate);
        }
        URI endpoint = validateRerankConfiguration();
        ObjectNode payload = buildRerankPayload(query, candidates);
        JsonNode response = requestRerank(endpoint, payload);
        return parseRerankResponse(response, candidates);
    }

    /** 使用百炼 input / parameters 请求结构，要求返回全部候选供搜索入口截取。 */
    private ObjectNode buildRerankPayload(String query, List<RetrieveResult> candidates) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", rerankProperties.getModel());
        ObjectNode input = payload.putObject("input");
        input.put("query", query);
        ArrayNode documents = input.putArray("documents");
        for (RetrieveResult candidate : candidates) {
            documents.add(candidate.getContent());
        }
        ObjectNode parameters = payload.putObject("parameters");
        parameters.put("top_n", candidates.size());
        parameters.put("return_documents", false);
        return payload;
    }

    private URI validateRerankConfiguration() {
        if (!StringUtils.hasText(rerankProperties.getEndpoint())
                || !StringUtils.hasText(rerankProperties.getModel())
                || !StringUtils.hasText(rerankProperties.getApiKey())) {
            throw new IllegalStateException("请配置 rag.rerank.endpoint、model 和 api-key");
        }
        URI endpoint = URI.create(rerankProperties.getEndpoint());
        if ((!"https".equalsIgnoreCase(endpoint.getScheme()) && !"http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("rag.rerank.endpoint 必须为有效的 HTTP(S) 完整请求地址");
        }
        return endpoint;
    }

    private JsonNode requestRerank(URI endpoint, ObjectNode payload) {
        HttpPost request = new HttpPost(endpoint);
        request.setHeader("Authorization", "Bearer " + rerankProperties.getApiKey());
        request.setHeader("Accept", "application/json");
        try {
            request.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));
            return httpClient.execute(request, response -> {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    throw new IOException("重排请求失败，HTTP " + response.getCode());
                }
                if (response.getEntity() == null) {
                    throw new IOException("重排响应为空");
                }
                byte[] responseBytes;
                try (InputStream input = response.getEntity().getContent()) {
                    responseBytes = input.readNBytes(rerankProperties.getMaxResponseBytes() + 1);
                }
                if (responseBytes.length > rerankProperties.getMaxResponseBytes()) {
                    throw new IOException("重排响应超过配置的大小上限");
                }
                return objectMapper.readTree(responseBytes);
            });
        } catch (IOException exception) {
            // 不在异常中拼接密钥、候选正文或完整服务端响应。
            throw new IllegalStateException("重排模型调用失败", exception);
        }
    }

    /** 百炼不同模型的 results 可能位于顶层，也可能位于 output 内。 */
    private List<RetrieveResult> parseRerankResponse(JsonNode response, List<RetrieveResult> candidates) {
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("重排响应不是合法对象");
        }
        if (response.hasNonNull("code")) {
            throw new IllegalStateException("重排服务返回业务错误");
        }
        JsonNode results = response.path("output").path("results");
        if (!results.isArray()) {
            results = response.path("results");
        }
        if (!results.isArray() || results.size() != candidates.size()) {
            throw new IllegalStateException("重排返回数量与候选数量不一致");
        }

        boolean[] seenIndexes = new boolean[candidates.size()];
        List<RetrieveResult> rerankedResults = new ArrayList<>();
        for (JsonNode item : results) {
            JsonNode indexNode = item.path("index");
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                throw new IllegalStateException("重排返回的候选序号不是合法整数");
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= candidates.size() || seenIndexes[index]) {
                throw new IllegalStateException("重排返回的候选序号越界或重复");
            }
            seenIndexes[index] = true;
            JsonNode scoreNode = item.path("relevance_score");
            if (!scoreNode.isNumber() || !Double.isFinite(scoreNode.doubleValue())) {
                throw new IllegalStateException("重排返回的相关性分数不合法");
            }
            RetrieveResult source = candidates.get(index);
            RetrieveResult result = RetrieveResult.builder()
                    .id(source.getId())
                    .content(source.getContent())
                    .vectorRank(source.getVectorRank())
                    .bm25Rank(source.getBm25Rank())
                    .rrfScore(source.getRrfScore())
                    .rerankScore(scoreNode.doubleValue())
                    .build();
            rerankedResults.add(result);
        }
        rerankedResults.sort(Comparator
                .comparing(RetrieveResult::getRerankScore, Comparator.reverseOrder())
                .thenComparing(RetrieveResult::getId));
        return rerankedResults;
    }

    private void validateCandidate(RetrieveResult result) {
        if (result == null || !StringUtils.hasText(result.getId()) || !StringUtils.hasText(result.getContent())) {
            throw new IllegalArgumentException("候选 Chunk 的 ID 和正文不能为空");
        }
    }

    private void validateRank(Integer rank) {
        if (rank == null || rank < 1) {
            throw new IllegalArgumentException("召回排名必须从 1 开始");
        }
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        httpClient.close();
    }
}
