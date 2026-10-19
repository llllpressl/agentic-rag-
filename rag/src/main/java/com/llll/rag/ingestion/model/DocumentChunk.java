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

    /** 原约定：连续原文切片，按序直接拼接可还原解析源文。 */
    /** 规范化后的检索正文，也是 Embedding 输入；允许重复必要标题、表头及结构标记，不重复正文。 */
    private String content;
}
