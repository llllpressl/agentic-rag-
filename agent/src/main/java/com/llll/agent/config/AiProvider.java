package com.llll.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties("agent-ai")
public class AiProvider {

    private DeepSeek deepseek = new DeepSeek();

    @Data
    public static class DeepSeek{

        private String url;

        private String apiKey;

        private String endpoint;

    }
}
