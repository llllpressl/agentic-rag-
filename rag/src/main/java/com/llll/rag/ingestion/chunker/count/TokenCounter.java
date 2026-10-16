package com.llll.rag.ingestion.chunker.count;

/** 分块长度使用字符预算；此接口仅提供后续入库所需的 Token 计数。 */
public interface TokenCounter {

    int count(String text);
}
