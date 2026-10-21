package com.llll.rag.search.routing;

/** 只表示外部 Jev 调用或协议校验失败；取消和编程错误不属于此异常。 */
public class JevCallException extends RuntimeException {

    public JevCallException(String message) {
        super(message);
    }
}
