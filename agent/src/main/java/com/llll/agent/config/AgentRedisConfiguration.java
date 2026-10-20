package com.llll.agent.config;

import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class AgentRedisConfiguration {

    private final AgentRedisProperties redisProperties;
    private final AgentProperties agentProperties;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient agentRedissonClient() {
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        config.setLockWatchdogTimeout(agentProperties.getLockWatchdogTimeoutMs());
        SingleServerConfig server = config.useSingleServer();
        server.setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort());
        server.setDatabase(redisProperties.getDatabase());
        server.setConnectTimeout(timeoutMillis(redisProperties.getConnectTimeout(), "connect-timeout"));
        server.setTimeout(timeoutMillis(redisProperties.getTimeout(), "timeout"));
        // 加锁结果不明确时由准入组件补偿，不自动重试加锁命令，避免重入计数增加。
        server.setRetryAttempts(0);
        server.setConnectionMinimumIdleSize(1);
        server.setConnectionPoolSize(8);
        if (redisProperties.getUsername() != null && !redisProperties.getUsername().isBlank()) {
            config.setUsername(redisProperties.getUsername());
        }
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            config.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    private int timeoutMillis(Duration timeout, String property) {
        long millis = timeout.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("spring.data.redis." + property + " 必须为正数且不超过 int 毫秒范围");
        }
        return (int) millis;
    }
}
