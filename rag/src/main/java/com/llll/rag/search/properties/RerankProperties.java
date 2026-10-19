package com.llll.rag.search.properties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** endpoint 为完整的百炼文本排序 HTTP 地址。密钥在实际重排时检查。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.rerank")
public class RerankProperties {

    private String endpoint;

    private String model;

    private String apiKey;

    private Duration connectTimeout;

    private Duration requestTimeout;

    private int maxResponseBytes;

    @PostConstruct
    public void validate() {
        if (connectTimeout == null || requestTimeout == null
                || connectTimeout.toMillis() < 1 || requestTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("重排连接超时和请求超时必须大于 0 毫秒");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("重排响应大小上限必须在 1 字节到 16 MB 之间");
        }
    }
}
