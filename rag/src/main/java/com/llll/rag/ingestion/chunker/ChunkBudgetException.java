package com.llll.rag.ingestion.chunker;

/** 仅表示预算不足；调用方可以减少标题上下文后重试，结构错误不能这样降级。 */
class ChunkBudgetException extends IllegalArgumentException {

    ChunkBudgetException(String message) {
        super(message);
    }
}
