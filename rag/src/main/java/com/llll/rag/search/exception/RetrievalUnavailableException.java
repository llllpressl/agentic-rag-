package com.llll.rag.search.exception;

/** 没有可用证据，且至少一路召回失败；工具层据此明确返回“检索不可用”。 */
public class RetrievalUnavailableException extends RuntimeException {

    public RetrievalUnavailableException() {
        super("检索不可用");
    }
}
