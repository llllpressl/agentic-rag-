package com.llll.rag.search.routing;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jev")
public class JevProperties {

    private boolean enabled;

    private String baseUrl = "https://api.typesafe.ai";

    private String apiKey;

    private String model = "jev-latest";

    private Duration connectTimeout = Duration.ofMillis(1500);

    private Duration requestTimeout = Duration.ofSeconds(3);

    private int maxResponseBytes = 2097152;

    @PostConstruct
    public void validate() {
        validateTimeout(connectTimeout, "connect-timeout");
        validateTimeout(requestTimeout, "request-timeout");
        if (maxResponseBytes < 1 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("jev.max-response-bytes 必须在 1 字节到 16 MB 之间");
        }
        if (enabled) {
            endpoint();
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("jev.model 不能为空");
            }
        }
    }

    public URI endpoint() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("jev.base-url 不能为空");
        }
        URI address;
        try {
            address = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("jev.base-url 必须是有效的 HTTP(S) 服务地址");
        }
        if ((!"https".equalsIgnoreCase(address.getScheme()) && !"http".equalsIgnoreCase(address.getScheme()))
                || address.getHost() == null || address.getUserInfo() != null
                || address.getQuery() != null || address.getFragment() != null
                || (address.getPath() != null && !address.getPath().isEmpty() && !"/".equals(address.getPath()))) {
            throw new IllegalArgumentException("jev.base-url 必须是有效的 HTTP(S) 服务根地址");
        }
        return address.resolve("/v1/systemone");
    }

    private void validateTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("jev." + name + " 必须在 1 毫秒到 1 分钟之间");
        }
    }
}
