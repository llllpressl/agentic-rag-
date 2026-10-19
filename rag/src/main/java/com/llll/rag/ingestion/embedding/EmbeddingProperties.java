package com.llll.rag.ingestion.embedding;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Embedding 参数由 application.yaml 统一提供。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "knowledge.ingestion.embedding")
public class EmbeddingProperties {

    private String baseUrl;
    private String apiKey;
    private String model;
    private int dimensions;
    private int batchSize;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private int maxResponseBytes;
}
