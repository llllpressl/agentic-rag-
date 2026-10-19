package com.llll.rag.ingestion.model;

/** 解析阶段保留的内容结构，只有顶层 HEADING 可以更新章节路径。 */
public enum DocumentBlockType {
    HEADING,
    PARAGRAPH,
    LIST,
    LIST_ITEM,
    TABLE,
    CODE_BLOCK,
    QUOTE,
    RAW
}
