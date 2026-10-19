package com.llll.rag.search.config;

import com.llll.rag.search.properties.RagSearchProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 两路使用独立且有容量限制的执行器；拒绝任务时交给检索入口降级。 */
@Configuration(proxyBeanMethods = false)
public class RagRecallExecutorConfiguration {

    @Bean
    public ThreadPoolTaskExecutor vectorRecallExecutor(RagSearchProperties properties) {
        return createExecutor("rag-vector-", properties.getVectorPoolSize(), properties.getVectorQueueCapacity());
    }

    @Bean
    public ThreadPoolTaskExecutor keywordRecallExecutor(RagSearchProperties properties) {
        return createExecutor("rag-keyword-", properties.getKeywordPoolSize(), properties.getKeywordQueueCapacity());
    }

    private ThreadPoolTaskExecutor createExecutor(String threadNamePrefix, int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setStrictEarlyShutdown(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
