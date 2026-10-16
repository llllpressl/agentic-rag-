package com.llll.rag.ingestion.parser.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 云 API 的文件上传、轮询与结果读取；不操作业务数据库。 */
@Component
public class MinerUClient implements AutoCloseable {

    private final MinerUProperties properties;
    private final MinerUArchiveReader archiveReader;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CloseableHttpClient apiClient;
    private final CloseableHttpClient objectClient;

    public MinerUClient(MinerUProperties properties, MinerUArchiveReader archiveReader) {
        validateConfiguration(properties);
        this.properties = properties;
        this.archiveReader = archiveReader;
        apiClient = createClient(false);
        objectClient = createClient(true);
    }

    public String extractMarkdown(File file) {
        if (file == null || !file.isFile() || file.length() == 0) {
            throw new IllegalArgumentException("MinerU 源文件不存在或为空");
        }
        String extension = StringUtils.getFilenameExtension(file.getName());
        if (extension == null || !Set.of("pdf", "doc", "docx").contains(extension.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("MinerU 源文件必须保留 pdf/doc/docx 扩展名");
        }
        if (file.length() > properties.getMaxFileBytes()) {
            throw new IllegalArgumentException("MinerU 源文件大小超出本项目上限");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("未配置 knowledge.ingestion.mineru.api-key");
        }
        try {
            requireNotInterrupted();
            String dataId = UUID.randomUUID().toString();
            var payload = mapper.createObjectNode();
            payload.put("model_version", properties.getModelVersion());
            payload.putArray("files").addObject().put("name", file.getName()).put("data_id", dataId);
            var apply = new HttpPost(apiUri("/api/v4/file-urls/batch"));
            apply.setEntity(new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));
            JsonNode uploadData = callApi(apply);
            String batchId = requireText(uploadData, "batch_id");
            JsonNode uploadUrls = uploadData.path("file_urls");
            if (!uploadUrls.isArray() || uploadUrls.size() != 1 || !uploadUrls.get(0).isTextual()) {
                throw new IOException("MinerU 没有返回唯一文件上传地址");
            }
            // 签名地址自己包含访问凭据，不能附加 MinerU 的 Bearer 密钥。
            var upload = new HttpPut(requireHttpUri(uploadUrls.get(0).asText()));
            upload.setEntity(new FileEntity(file, (ContentType) null));
            requireNotInterrupted();
            objectClient.execute(upload, response -> {
                requireSuccess(response, "上传源文件");
                return null;
            });
            URI archiveUri = waitForArchive(batchId, dataId);
            requireNotInterrupted();
            return objectClient.execute(new HttpGet(archiveUri), response -> {
                requireSuccess(response, "下载解析结果");
                if (response.getEntity() == null) {
                    throw new IOException("MinerU 解析结果下载为空");
                }
                if (response.getEntity().getContentLength() > properties.getMaxArchiveBytes()) {
                    throw new IOException("MinerU ZIP 下载量超出上限");
                }
                try (var stream = response.getEntity().getContent()) {
                    return archiveReader.readMarkdown(stream);
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MinerU 解析已中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("MinerU 解析调用失败", exception);
        }
    }

    private URI waitForArchive(String batchId, String dataId) throws IOException, InterruptedException {
        long started = System.nanoTime();
        long budget = properties.getTaskTimeout().toNanos();
        String encodedId = URLEncoder.encode(batchId, StandardCharsets.UTF_8);
        while (System.nanoTime() - started < budget) {
            requireNotInterrupted();
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) {
                break;
            }
            var query = new HttpGet(apiUri("/api/v4/extract-results/batch/" + encodedId));
            long requestMillis = Math.max(1, Math.min(properties.getRequestTimeout().toMillis(),
                    Duration.ofNanos(remaining).toMillis()));
            query.setConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(requestMillis))
                    .setResponseTimeout(Timeout.ofMilliseconds(requestMillis))
                    .setRedirectsEnabled(false).build());
            JsonNode results = callApi(query).path("extract_result");
            if (!results.isArray() || results.size() != 1 || !results.get(0).isObject()) {
                throw new IOException("MinerU 返回的解析结果与单个源文件不匹配");
            }
            JsonNode result = results.get(0);
            String returnedId = result.path("data_id").asText("");
            if (!returnedId.isBlank() && !dataId.equals(returnedId)) {
                throw new IOException("MinerU 返回了其他文件的解析结果");
            }
            String state = requireText(result, "state");
            switch (state) {
                case "done":
                    return requireHttpUri(requireText(result, "full_zip_url"));
                case "failed":
                    throw new IOException("MinerU 文档解析失败：" + safeMessage(result.path("err_msg").asText()));
                case "waiting-file", "pending", "running", "converting":
                    break;
                default:
                    throw new IOException("MinerU 返回未知解析状态");
            }
            long sleep = Math.min(properties.getPollInterval().toNanos(),
                    Math.max(0, budget - (System.nanoTime() - started)));
            if (sleep > 0) {
                Thread.sleep(Duration.ofNanos(sleep));
            }
        }
        throw new IOException("MinerU 解析轮询超时");
    }

    private JsonNode callApi(HttpUriRequestBase request) throws IOException {
        request.setHeader("Authorization", "Bearer " + properties.getApiKey());
        request.setHeader("Accept", "application/json");
        return apiClient.execute(request, response -> {
            requireSuccess(response, "调用 API");
            if (response.getEntity() == null) {
                throw new IOException("MinerU API 响应为空");
            }
            byte[] bytes;
            try (var stream = response.getEntity().getContent()) {
                bytes = stream.readNBytes(1024 * 1024 + 1);
            }
            if (bytes.length > 1024 * 1024) {
                throw new IOException("MinerU API 响应超出上限");
            }
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !"0".equals(root.path("code").asText())) {
                String message = root == null ? "响应不是 JSON 对象" : root.path("msg").asText();
                throw new IOException("MinerU API 拒绝请求：" + safeMessage(message));
            }
            if (!root.path("data").isObject()) {
                throw new IOException("MinerU API 响应缺少 data");
            }
            return root.path("data");
        });
    }

    private CloseableHttpClient createClient(boolean allowRedirects) {
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(properties.getRequestTimeout().toMillis())).build())
                .build();
        return HttpClients.custom().setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(properties.getRequestTimeout().toMillis()))
                        .setRedirectsEnabled(allowRedirects).build())
                // 避免 POST 超时后自动再创建一次远端任务。
                .disableAutomaticRetries().build();
    }

    private URI apiUri(String path) {
        return requireHttpUri(properties.getBaseUrl().replaceAll("/+$", "") + path);
    }

    private static URI requireHttpUri(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("MinerU 地址不能为空，请检查 YAML 配置");
        }
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("MinerU 地址必须为有效的 HTTP(S) URL");
        }
        return uri;
    }

    private static String requireText(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IOException("MinerU 响应缺少字段：" + field);
        }
        return value.asText();
    }

    private void requireSuccess(ClassicHttpResponse response, String operation) throws IOException {
        if (response.getCode() < 200 || response.getCode() >= 300) {
            throw new IOException("MinerU " + operation + "失败，HTTP " + response.getCode());
        }
    }

    private String safeMessage(String message) {
        String value = message.replace(properties.getApiKey(), "[redacted]");
        return value.substring(0, Math.min(value.length(), 300));
    }

    private static void requireNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("解析线程已中断");
        }
    }

    private static void validateConfiguration(MinerUProperties properties) {
        requireHttpUri(properties.getBaseUrl());
        if (properties.getModelVersion() == null
                || !Set.of("pipeline", "vlm").contains(properties.getModelVersion())) {
            throw new IllegalArgumentException("PDF/Word 的 MinerU 模型版本须为 pipeline 或 vlm");
        }
        for (Duration duration : new Duration[]{properties.getConnectTimeout(), properties.getRequestTimeout(),
                properties.getPollInterval(), properties.getTaskTimeout()}) {
            if (duration == null || duration.isNegative() || duration.toMillis() <= 0) {
                throw new IllegalArgumentException("MinerU 超时和轮询间隔须大于 0 毫秒");
            }
        }
        if (properties.getMaxFileBytes() <= 0 || properties.getMaxArchiveBytes() <= 0
                || properties.getMaxUncompressedBytes() <= 0 || properties.getMaxMarkdownBytes() <= 0) {
            throw new IllegalArgumentException("MinerU 文件及结果大小上限须大于 0");
        }
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        try {
            apiClient.close();
        } finally {
            objectClient.close();
        }
    }
}
