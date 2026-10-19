package com.llll.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 工具输出限额按 Unicode 字符计算，不改变 RAG 内部候选和排序。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.tools.knowledge-search")
public class KnowledgeSearchToolProperties {

    private int maxChunkCharacters = 2000;

    private int maxResultCharacters = 12000;

    @PostConstruct
    public void validate() {
        if (maxChunkCharacters < 100 || maxChunkCharacters > 100000) {
            throw new IllegalArgumentException("工具单片段字符上限必须在 100 到 100000 之间");
        }
        if (maxResultCharacters < maxChunkCharacters + 512 || maxResultCharacters > 1000000) {
            throw new IllegalArgumentException("工具总字符上限必须至少比单片段上限多 512，且不超过 1000000");
        }
    }
}
