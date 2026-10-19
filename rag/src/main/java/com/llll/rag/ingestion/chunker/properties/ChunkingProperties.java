package com.llll.rag.ingestion.chunker.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "knowledge.ingestion.chunking")
public class ChunkingProperties {

    /** 最终 Chunk 文本的目标 Unicode 码点数。 */
    private int targetCharacters = 600;

    /** 包括标题与分隔符在内的硬上限。 */
    private int maxCharacters = 1000;

    /** 原文保真模式不重复 content，必须为 0。 */
    private int overlapCharacters = 0;
}
