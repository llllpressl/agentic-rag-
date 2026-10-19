package com.llll.rag.search.properties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 所有数量参数均从 rag.search 读取，不在搜索流程中写死。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.search")
public class RagSearchProperties {

    private int vectorTopK;

    private int bm25TopK;

    private int fusionTopK;

    private int finalTopK;

    private int rrfK;

    private int maxQueryLength;

    private Duration vectorTimeout = Duration.ofSeconds(5);

    private Duration keywordTimeout = Duration.ofSeconds(3);

    private Duration recallTimeout = Duration.ofSeconds(5);

    private int vectorPoolSize = 4;

    private int keywordPoolSize = 4;

    private int vectorQueueCapacity = 16;

    private int keywordQueueCapacity = 16;

    @PostConstruct
    public void validateRecallConfiguration() {
        validateRecallTimeout(vectorTimeout, "vector-timeout");
        validateRecallTimeout(keywordTimeout, "keyword-timeout");
        validateRecallTimeout(recallTimeout, "recall-timeout");
        if (vectorPoolSize < 1 || keywordPoolSize < 1) {
            throw new IllegalArgumentException("召回执行器线程数必须为正数");
        }
        if (vectorQueueCapacity < 0 || keywordQueueCapacity < 0) {
            throw new IllegalArgumentException("召回执行器队列容量不能小于 0");
        }
    }

    private void validateRecallTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("rag.search." + name + " 必须在 1 毫秒到 1 分钟之间");
        }
    }

    @PostConstruct
    public void validate() {
        if (vectorTopK < 1 || bm25TopK < 1 || fusionTopK < 1 || finalTopK < 1) {
            throw new IllegalArgumentException("rag.search 的 Top-K 配置必须为正数");
        }
        if (finalTopK > fusionTopK) {
            throw new IllegalArgumentException("final-top-k 不能大于 fusion-top-k");
        }
        if (vectorTopK > 500 || bm25TopK > 500 || fusionTopK > 500) {
            throw new IllegalArgumentException("单问题召回和融合数量不能超过 500");
        }
        if (rrfK < 1 || maxQueryLength < 1) {
            throw new IllegalArgumentException("rrf-k 和 max-query-length 必须为正数");
        }
    }
}
