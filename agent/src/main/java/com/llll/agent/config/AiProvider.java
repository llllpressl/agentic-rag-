package com.llll.agent.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
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
