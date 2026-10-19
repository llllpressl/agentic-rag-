package com.llll.rag.knowledge.service;

/** KB 已逻辑删除，但残余清理尚未完成；允许再次调用删除接口。 */
public class KnowledgeBaseCleanupException extends RuntimeException {

    public KnowledgeBaseCleanupException(String message, Throwable cause) {
        super(message, cause);
    }
}
