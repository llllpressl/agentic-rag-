package com.llll.rag.search.properties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
