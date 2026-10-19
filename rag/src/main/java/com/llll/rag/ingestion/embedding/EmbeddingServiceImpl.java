package com.llll.rag.ingestion.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
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
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingServiceImpl implements EmbeddingService, AutoCloseable {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient;

    public EmbeddingServiceImpl(EmbeddingProperties properties) {
        validateConfiguration(properties);
        this.properties = properties;
        this.httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
                                .setSocketTimeout(Timeout.ofMilliseconds(properties.getRequestTimeout().toMillis()))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(properties.getRequestTimeout().toMillis()))
                        .setRedirectsEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .build();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        for (String text : texts) {
            if (!StringUtils.hasText(text)) {
                throw new IllegalArgumentException("待向量化正文不能为空");
            }
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("未配置 knowledge.ingestion.embedding.api-key");
        }

        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += properties.getBatchSize()) {
            int end = Math.min(start + properties.getBatchSize(), texts.size());
            List<String> batch = texts.subList(start, end);
            JsonNode response = requestBatch(batch);
            List<float[]> batchVectors = parseResponse(response, batch.size());
            vectors.addAll(batchVectors);
        }
        return List.copyOf(vectors);
    }

    /** 将一批文本发送给兼容接口；不进行应用层重试。 */
    private JsonNode requestBatch(List<String> texts) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", properties.getModel());
        payload.put("dimensions", properties.getDimensions());
        payload.put("encoding_format", "float");
        ArrayNode input = payload.putArray("input");
        for (String text : texts) {
            input.add(text);
        }

        URI endpoint = URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/embeddings");
        HttpPost request = new HttpPost(endpoint);
        request.setHeader("Authorization", "Bearer " + properties.getApiKey());
        request.setHeader("Accept", "application/json");
        try {
            request.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));
            return httpClient.execute(request, response -> {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    throw new IOException("Embedding 请求失败，HTTP " + response.getCode());
                }
                if (response.getEntity() == null) {
                    throw new IOException("Embedding 响应为空");
                }
                byte[] responseBytes;
                try (InputStream stream = response.getEntity().getContent()) {
                    responseBytes = stream.readNBytes(properties.getMaxResponseBytes() + 1);
                }
                if (responseBytes.length > properties.getMaxResponseBytes()) {
                    throw new IOException("Embedding 响应超出大小上限");
                }
                return objectMapper.readTree(responseBytes);
            });
        } catch (IOException exception) {
            // 错误中不拼接正文、密钥或完整的远端响应。
            throw new IllegalStateException("Embedding 调用失败", exception);
        }
    }

    /** 按响应 index 恢复本批输入顺序，并校验每条向量。 */
    private List<float[]> parseResponse(JsonNode response, int expectedCount) {
        if (response == null || !response.path("data").isArray()) {
            throw new IllegalStateException("Embedding 响应缺少 data 数组");
        }
        JsonNode data = response.path("data");
        if (data.size() != expectedCount) {
            throw new IllegalStateException("Embedding 返回数量与输入数量不一致");
        }

        List<float[]> orderedVectors = new ArrayList<>();
        for (int index = 0; index < expectedCount; index++) {
            orderedVectors.add(null);
        }
        for (JsonNode item : data) {
            JsonNode indexNode = item.path("index");
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                throw new IllegalStateException("Embedding 响应序号不是合法整数");
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= expectedCount || orderedVectors.get(index) != null) {
                throw new IllegalStateException("Embedding 响应序号越界或重复");
            }
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.size() != properties.getDimensions()) {
                throw new IllegalStateException("Embedding 向量维度与配置不一致");
            }
            float[] vector = new float[properties.getDimensions()];
            boolean hasNonZeroValue = false;
            for (int dimension = 0; dimension < vector.length; dimension++) {
                JsonNode valueNode = embedding.get(dimension);
                if (!valueNode.isNumber()) {
                    throw new IllegalStateException("Embedding 向量元素不是数值");
                }
                float value = valueNode.floatValue();
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("Embedding 向量包含非有限数值");
                }
                vector[dimension] = value;
                if (value != 0.0f) {
                    hasNonZeroValue = true;
                }
            }
            if (!hasNonZeroValue) {
                throw new IllegalStateException("Embedding 返回了零向量");
            }
            orderedVectors.set(index, vector);
        }
        return orderedVectors;
    }

    private void validateConfiguration(EmbeddingProperties configuration) {
        if (!StringUtils.hasText(configuration.getBaseUrl()) || !StringUtils.hasText(configuration.getModel())) {
            throw new IllegalArgumentException("Embedding 地址和模型不能为空");
        }
        URI baseUri = URI.create(configuration.getBaseUrl());
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("Embedding 地址必须为有效的 HTTP(S) 基础地址");
        }
        if (configuration.getDimensions() != 1024) {
            throw new IllegalArgumentException("当前 PG 分块表要求 1024 维向量");
        }
        if (configuration.getBatchSize() <= 0 || configuration.getBatchSize() > 10) {
            throw new IllegalArgumentException("当前 Embedding 单批输入数必须在 1 到 10 之间");
        }
        requirePositiveTimeout(configuration.getConnectTimeout());
        requirePositiveTimeout(configuration.getRequestTimeout());
        if (configuration.getMaxResponseBytes() <= 0 || configuration.getMaxResponseBytes() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Embedding 响应大小上限不合法");
        }
    }

    private void requirePositiveTimeout(Duration timeout) {
        if (timeout == null || timeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Embedding 超时配置必须大于 0 毫秒");
        }
    }

    @PreDestroy
    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
