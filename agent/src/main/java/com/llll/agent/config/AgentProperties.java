package com.llll.agent.config;

import lombok.Data;
import jakarta.validation.constraints.Min;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@ConfigurationProperties("agent")
@Validated
public class AgentProperties {

    private Chat chat = new Chat();

    private Integer maxIters = 10;

    private Integer maxRetries = 1;

    @Min(1)
    private int maxConcurrentRunsPerUser = 5;

    @Min(1000)
    private long lockWatchdogTimeoutMs = 30_000L;

    @NotNull
    private Long sseTimeoutMs = 900_000L;

    @Data
    public static class Chat{

        private String provider;

        private String model;
    }
}
