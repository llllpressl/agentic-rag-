package com.llll.rag.ingestion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分块器输出的检索文本，后续用于向量化和持久化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    /** 文档内分块序号，从 0 开始且连续。 */
    private Integer index;

    /** 原字段说明：组装后的标题和正文，也是本次流程的 Embedding 输入。 */
    /** 连续原文切片，也是 Embedding 输入；按序直接拼接可还原解析源文，不补标题或重叠文本。 */
    private String content;
}
