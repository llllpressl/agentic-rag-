package com.llll.rag.ingestion.parser.mineru;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "knowledge.ingestion.mineru")
public class MinerUProperties {

    // 配置统一放在 application.yaml，本类只负责绑定。
    private String baseUrl;
    private String apiKey;
    private String modelVersion;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration pollInterval;
    private Duration taskTimeout;
    private long maxFileBytes;
    private long maxArchiveBytes;
    private long maxUncompressedBytes;
    private int maxMarkdownBytes;
}
