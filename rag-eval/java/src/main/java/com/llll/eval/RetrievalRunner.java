package com.llll.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llll.rag.ingestion.embedding.EmbeddingProperties;
import com.llll.rag.ingestion.embedding.EmbeddingService;
import com.llll.rag.search.model.RetrieveResult;
import com.llll.rag.search.properties.RagSearchProperties;
import com.llll.rag.search.properties.RerankProperties;
import com.llll.rag.search.request.RagSearchRequest;
import com.llll.rag.search.retriever.KeywordRetriever;
import com.llll.rag.search.retriever.VectorRetriever;
import com.llll.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 一道问题只召回一次，四种方案共享候选；诊断为串行，生产耗时另行调用。 */
@RequiredArgsConstructor
public class RetrievalRunner {

    private static final Set<String> MODES = Set.of("VECTOR_ONLY", "BM25_ONLY", "HYBRID_RRF", "HYBRID_RERANK");
    private final EmbeddingService embeddingService;
    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final RagService ragService;
    private final RagSearchProperties searchProperties;
    private final EmbeddingProperties embeddingProperties;
    private final RerankProperties rerankProperties;
    private final EvaluationSnapshot snapshot;
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    public void run() throws Exception {
        Path input = Path.of(environment.getRequiredProperty("eval.input"));
        Path output = Path.of(environment.getProperty("eval.output", "rag-eval/results"));
        Path snapshotFile = Path.of(environment.getProperty("eval.snapshot", "rag-eval/datasets/snapshot.json"));
        int limit = environment.getProperty("eval.limit", Integer.class, 10);
        int concurrency = environment.getProperty("eval.concurrency", Integer.class, 1);
        int timeout = environment.getProperty("eval.timeout-seconds", Integer.class, 180);
        if (limit < 1 || concurrency < 1 || concurrency > 32 || timeout < 1) {
            throw new IllegalArgumentException("评测数量、并发或超时参数非法");
        }
        if (searchProperties.getVectorTopK() != 15 || searchProperties.getBm25TopK() != 15
                || searchProperties.getFusionTopK() != 10 || searchProperties.getFinalTopK() != 5) {
            throw new IllegalArgumentException("评测要求召回 15、融合 10、最终 5，请在 CLI 覆盖配置");
        }
        List<String> modes = Arrays.asList(environment.getProperty("eval.modes",
                "VECTOR_ONLY,BM25_ONLY,HYBRID_RRF,HYBRID_RERANK").split(","));
        if (modes.isEmpty() || !MODES.containsAll(modes) || Set.copyOf(modes).size() != modes.size()) {
            throw new IllegalArgumentException("检索模式非法或重复");
        }
        List<JsonNode> questions = new ArrayList<>();
        Map<String, List<JsonNode>> categories = new LinkedHashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        String expectedSnapshot = mapper.readTree(snapshotFile.toFile()).path("snapshot_id").asText();
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode question = mapper.readTree(line);
            String id = question.path("id").asText();
            if (id.isBlank() || !seen.add(id) || question.path("query").asText().isBlank()
                    || !expectedSnapshot.equals(question.path("snapshot_id").asText())) {
                throw new IllegalArgumentException("问题 ID、正文或快照无效");
            }
            if (!"rejected".equals(question.path("review_status").asText())) {
                String category = question.path("category").asText();
                categories.computeIfAbsent(category, ignored -> new ArrayList<>()).add(question);
            }
        }
        // 按题型轮转取样，避免 limit=30 时只拿到文件开头的事实题。
        int depth = 0;
        boolean added = true;
        while (questions.size() < limit && added) {
            added = false;
            for (List<JsonNode> category : categories.values()) {
                if (depth < category.size() && questions.size() < limit) {
                    questions.add(category.get(depth));
                    added = true;
                }
            }
            depth++;
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("没有可评测问题");
        }
        String pgBefore = snapshot.id(snapshot.chunks());
        if (!pgBefore.equals(expectedSnapshot)) {
            throw new IllegalStateException("PG 已偏离数据集快照，请重新导出并审核");
        }
        boolean needsEs = !modes.equals(List.of("VECTOR_ONLY"));
        String esBefore = needsEs ? snapshot.esId() : "not_used";
        Files.createDirectories(output);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("started_at", Instant.now().toString());
        manifest.put("snapshot_id", pgBefore);
        manifest.put("es_snapshot_id", esBefore);
        manifest.put("dataset_sha256", EvaluationSnapshot.hash(Files.readString(input, StandardCharsets.UTF_8)));
        List<String> questionIds = new ArrayList<>();
        for (JsonNode question : questions) {
            questionIds.add(question.path("id").asText());
        }
        manifest.put("question_ids", questionIds);
        manifest.put("modes", modes);
        manifest.put("concurrency", concurrency);
        manifest.put("sampling", "deterministic_category_round_robin");
        manifest.put("java_version", System.getProperty("java.version"));
        manifest.put("question_timeout_seconds", timeout);
        manifest.put("embedding_model", embeddingProperties.getModel());
        manifest.put("embedding_dimensions", embeddingProperties.getDimensions());
        manifest.put("rerank_model", rerankProperties.getModel());
        manifest.put("rrf_k", searchProperties.getRrfK());
        manifest.put("vector_top_k", 15);
        manifest.put("bm25_top_k", 15);
        manifest.put("fusion_top_k", 10);
        manifest.put("final_top_k", 5);
        manifest.put("snapshot_valid", false);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run_manifest.json").toFile(), manifest);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (java.io.BufferedWriter writer = Files.newBufferedWriter(output.resolve("retrieval_results.jsonl"), StandardCharsets.UTF_8)) {
            // 每批最多 concurrency 道，超时从任务提交算起，包含排队。
            for (int start = 0; start < questions.size(); start += concurrency) {
                List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();
                int end = Math.min(start + concurrency, questions.size());
                long submittedAt = System.nanoTime();
                for (int index = start; index < end; index++) {
                    JsonNode question = questions.get(index);
                    futures.add(executor.submit(() -> {
                        List<Map<String, Object>> results = retrieve(question, modes);
                        double elapsed = (System.nanoTime() - submittedAt) / 1_000_000.0;
                        for (Map<String, Object> row : results) {
                            row.put("question_elapsed_ms", elapsed);
                        }
                        return results;
                    }));
                }
                for (int index = start; index < end; index++) {
                    Future<List<Map<String, Object>>> future = futures.get(index - start);
                    List<Map<String, Object>> batch;
                    try {
                        long remaining = Math.max(0, TimeUnit.SECONDS.toNanos(timeout) - (System.nanoTime() - submittedAt));
                        batch = future.get(remaining, TimeUnit.NANOSECONDS);
                        if (((Number) batch.getFirst().get("question_elapsed_ms")).doubleValue() > timeout * 1000.0) {
                            throw new TimeoutException();
                        }
                    } catch (Exception exception) {
                        future.cancel(true);
                        batch = new ArrayList<>();
                        for (String mode : modes) {
                            Map<String, Object> row = base(questions.get(index), mode);
                            row.put("status", exception instanceof TimeoutException ? "TIMEOUT" : "FAILED");
                            row.put("failure_stage", "question");
                            row.put("error_type", exception.getClass().getSimpleName());
                            row.put("question_elapsed_ms", (System.nanoTime() - submittedAt) / 1_000_000.0);
                            row.put("latency_kind", "QUESTION_FAILURE_WALL_TIME");
                            batch.add(row);
                        }
                    }
                    for (Map<String, Object> row : batch) {
                        rows.add(row);
                        writer.write(mapper.writeValueAsString(row));
                        writer.newLine();
                    }
                    writer.flush();
                }
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        boolean valid = false;
        try {
            String pgAfter = snapshot.id(snapshot.chunks());
            String esAfter = needsEs ? snapshot.esId() : "not_used";
            manifest.put("pg_snapshot_after", pgAfter);
            manifest.put("es_snapshot_after", esAfter);
            valid = pgBefore.equals(pgAfter) && esBefore.equals(esAfter);
        } catch (Exception exception) {
            manifest.put("snapshot_check_error", exception.getClass().getSimpleName());
        }
        manifest.put("snapshot_valid", valid);
        manifest.put("finished_at", Instant.now().toString());
        manifest.put("result_count", rows.size());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run_manifest.json").toFile(), manifest);
        if (!valid) {
            throw new IllegalStateException("评测期间快照变化或核对失败；原始结果保留但不可形成正式结论");
        }
        System.out.println("RETRIEVAL_COMPLETED questions=" + questions.size() + ", rows=" + rows.size());
    }

    private List<Map<String, Object>> retrieve(JsonNode question, List<String> modes) throws Exception {
        String query = question.path("query").asText();
        if (query.codePointCount(0, query.length()) > searchProperties.getMaxQueryLength()) {
            throw new IllegalArgumentException("问题超过生产检索长度限制");
        }
        Map<String, Object> timings = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        List<RetrieveResult> vector = new ArrayList<>();
        List<RetrieveResult> keyword = new ArrayList<>();
        if (!modes.equals(List.of("BM25_ONLY"))) {
            List<float[]> vectors = stage("embedding", timings, errors, () -> embeddingService.embedBatch(List.of(query)));
            if (vectors != null && vectors.size() == 1) {
                List<RetrieveResult> found = stage("vector", timings, errors, () -> vectorRetriever.retrieve(vectors.getFirst(), 15));
                if (found != null) {
                    vector = found;
                }
            } else if (!errors.containsKey("embedding")) {
                errors.put("embedding", "InvalidVectorCount");
            }
        }
        if (!modes.equals(List.of("VECTOR_ONLY"))) {
            List<RetrieveResult> found = stage("bm25", timings, errors, () -> keywordRetriever.retrieve(query, 15));
            if (found != null) {
                keyword = found;
            }
        }
        List<RetrieveResult> vectorCandidates = vector;
        List<RetrieveResult> keywordCandidates = keyword;
        List<RetrieveResult> fused = new ArrayList<>();
        List<RetrieveResult> reranked = new ArrayList<>();
        if (modes.contains("HYBRID_RRF") || modes.contains("HYBRID_RERANK")) {
            List<RetrieveResult> found = stage("rrf", timings, errors, () -> ragService.rrf(vectorCandidates, keywordCandidates, 10));
            if (found != null) {
                fused = found;
            }
        }
        if (modes.contains("HYBRID_RERANK") && !errors.containsKey("rrf")) {
            List<RetrieveResult> fusionCandidates = fused;
            List<RetrieveResult> found = stage("rerank", timings, errors, () -> ragService.rerank(query, fusionCandidates));
            if (found != null) {
                reranked = found;
            }
        }
        Map<String, Object> production = new LinkedHashMap<>();
        if (environment.getProperty("eval.production-latency", Boolean.class, false)) {
            long startedAt = System.nanoTime();
            try {
                RagSearchRequest request = RagSearchRequest.builder()
                        .query(query)
                        .build();
                List<RetrieveResult> found = ragService.search(request);
                production.put("status", "COMPLETED_DEGRADATION_UNKNOWN");
                production.put("results", found);
            } catch (Exception exception) {
                production.put("status", "FAILED");
                production.put("error_type", exception.getClass().getSimpleName());
            }
            production.put("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000.0);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String mode : modes) {
            Map<String, Object> row = base(question, mode);
            List<String> stages = switch (mode) {
                case "VECTOR_ONLY" -> List.of("embedding", "vector");
                case "BM25_ONLY" -> List.of("bm25");
                case "HYBRID_RRF" -> List.of("embedding", "vector", "bm25", "rrf");
                default -> List.of("embedding", "vector", "bm25", "rrf", "rerank");
            };
            double elapsed = 0;
            Map<String, Object> modeTimings = new LinkedHashMap<>();
            Map<String, String> modeErrors = new LinkedHashMap<>();
            for (String name : stages) {
                if (timings.containsKey(name)) {
                    modeTimings.put(name, timings.get(name));
                    elapsed += ((Number) timings.get(name)).doubleValue();
                }
                if (errors.containsKey(name)) {
                    modeErrors.put(name, errors.get(name));
                }
            }
            List<RetrieveResult> found = switch (mode) {
                case "VECTOR_ONLY" -> vector;
                case "BM25_ONLY" -> keyword;
                case "HYBRID_RRF" -> fused;
                default -> reranked;
            };
            row.put("status", modeErrors.isEmpty() ? "SUCCESS" : "FAILED");
            row.put("stage_errors", modeErrors);
            row.put("stage_ms", modeTimings);
            row.put("diagnostic_elapsed_ms", elapsed);
            row.put("results", ranked(found));
            row.put("vector_candidates", vector);
            row.put("bm25_candidates", keyword);
            row.put("rrf_candidates", fused);
            row.put("rerank_status", mode.contains("RERANK")
                    ? (errors.containsKey("rrf") ? "NOT_RUN_RRF_FAILED" : (errors.containsKey("rerank")
                    ? "FAILED" : (fused.isEmpty() ? "EMPTY_INPUT" : "SUCCESS"))) : "NOT_REQUESTED");
            row.put("production", production);
            rows.add(row);
        }
        return rows;
    }

    private <T> T stage(String name, Map<String, Object> timings, Map<String, String> errors, Callable<T> task) {
        long startedAt = System.nanoTime();
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException();
            }
            return task.call();
        } catch (Exception exception) {
            errors.put(name, exception.getClass().getSimpleName());
            return null;
        } finally {
            timings.put(name, (System.nanoTime() - startedAt) / 1_000_000.0);
        }
    }

    private List<Map<String, Object>> ranked(List<RetrieveResult> found) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < Math.min(5, found.size()); index++) {
            RetrieveResult item = found.get(index);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chunk_id", item.getId());
            result.put("rank", index + 1);
            result.put("content", item.getContent());
            result.put("vector_rank", item.getVectorRank());
            result.put("bm25_rank", item.getBm25Rank());
            result.put("rrf_score", item.getRrfScore());
            result.put("rerank_score", item.getRerankScore());
            results.add(result);
        }
        return results;
    }

    private Map<String, Object> base(JsonNode question, String mode) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("question_id", question.path("id").asText());
        row.put("query", question.path("query").asText());
        row.put("snapshot_id", question.path("snapshot_id").asText());
        row.put("mode", mode);
        row.put("latency_kind", "SERIAL_DIAGNOSTIC");
        row.put("results", List.of());
        return row;
    }
}
