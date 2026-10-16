package com.llll.agent.config;

import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties("agent")
public class AgentProperties {

    private Chat chat = new Chat();

    private Integer maxIters = 10;

    private Integer maxRetries = 1;

    @NotNull
    private Long sseTimeoutMs = 900_000L;

    @Data
    public static class Chat{

        private String provider;

        private String model;
    }
}
