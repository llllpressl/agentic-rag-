package com.llll.rag.knowledge.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.llll.rag.ingestion.parser.mineru.MinerUArchiveReader;
import com.llll.rag.ingestion.parser.mineru.MinerUClient;
import com.llll.rag.ingestion.parser.mineru.MinerUProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** 本机 HTTP 替身验证协议和密钥传递；不会连接 MinerU 云服务。 */
class MinerUClientTest {

    @Test
    void uploadsPollsAndDownloadsWithoutLeakingBearerToSignedUrls(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("sample.pdf");
        Files.writeString(file, "%PDF-local-fixture");
        try (var fixture = new Fixture(false); var client = fixture.client()) {
            assertThat(client.extractMarkdown(file.toFile())).isEqualTo("# Redis\n缓存正文。");
            assertThat(fixture.submissions.get()).isEqualTo(1);
            assertThat(fixture.polls.get()).isEqualTo(2);
            assertThat(fixture.failure.get()).isNull();
        }
    }

    @Test
    void aFailedSubmissionIsNotAutomaticallyRepeated(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("sample.docx");
        Files.writeString(file, "local-fixture");
        try (var fixture = new Fixture(true); var client = fixture.client()) {
            assertThatThrownBy(() -> client.extractMarkdown(file.toFile()))
                    .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("MinerU 调用 API失败，HTTP 503");
            assertThat(fixture.submissions.get()).isEqualTo(1);
            assertThat(fixture.polls.get()).isZero();
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicInteger submissions = new AtomicInteger();
        private final AtomicInteger polls = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<String> dataId = new AtomicReference<>();
        private final boolean reject;

        private Fixture(boolean reject) throws IOException {
            this.reject = reject;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private MinerUClient client() {
            var properties = new MinerUProperties();
            properties.setBaseUrl(baseUrl());
            properties.setApiKey("unit-test-key");
            properties.setPollInterval(Duration.ofMillis(1));
            properties.setTaskTimeout(Duration.ofSeconds(5));
            properties.setRequestTimeout(Duration.ofSeconds(2));
            properties.setConnectTimeout(Duration.ofSeconds(2));
            return new MinerUClient(properties, new MinerUArchiveReader(properties));
        }

        private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                switch (path) {
                    case "/api/v4/file-urls/batch" -> {
                        submissions.incrementAndGet();
                        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer unit-test-key");
                        var request = mapper.readTree(exchange.getRequestBody());
                        dataId.set(request.path("files").get(0).path("data_id").asText());
                        respond(exchange, reject ? 503 : 200, mapper.writeValueAsBytes(Map.of("code", 0,
                                "data", Map.of("batch_id", "fixture-batch", "file_urls", new String[]{baseUrl() + "/upload"}))));
                    }
                    case "/upload" -> {
                        assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                        assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                                .isEqualTo("%PDF-local-fixture");
                        respond(exchange, 200, new byte[0]);
                    }
                    case "/api/v4/extract-results/batch/fixture-batch" -> {
                        assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer unit-test-key");
                        String state = polls.incrementAndGet() == 1 ? "pending" : "done";
                        respond(exchange, 200, mapper.writeValueAsBytes(Map.of("code", 0, "data",
                                Map.of("extract_result", new Object[]{Map.of("state", state,
                                        "data_id", dataId.get(), "full_zip_url", baseUrl() + "/result.zip")}))));
                    }
                    case "/result.zip" -> {
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                        respond(exchange, 200, MinerUArchiveReaderTest.zip(
                                Map.of("full.md", "# Redis\n缓存正文。".getBytes(StandardCharsets.UTF_8))));
                    }
                    default -> respond(exchange, 404, new byte[0]);
                }
            } catch (Throwable exception) {
                failure.set(exception);
                respond(exchange, 500, new byte[0]);
            } finally {
                exchange.close();
            }
        }

        private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() { server.stop(0); }
    }
}
