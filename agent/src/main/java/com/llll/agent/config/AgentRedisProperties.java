package com.llll.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 复用 spring.data.redis 配置，当前只连接单节点 Redis，不接管登录 Session。 */
@Data
@Configuration
@Validated
@ConfigurationProperties("spring.data.redis")
public class AgentRedisProperties {

    @NotBlank
    private String host = "127.0.0.1";

    @Min(1)
    @Max(65535)
    private int port = 6379;

    @Min(0)
    private int database = 0;

    private String username;

    @ToString.Exclude
    private String password;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(3);

    @NotNull
    private Duration timeout = Duration.ofSeconds(3);
}
