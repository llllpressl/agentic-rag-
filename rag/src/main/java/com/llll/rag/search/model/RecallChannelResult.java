package com.llll.rag.search.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 单次请求中一路召回的结果；成功但没有候选，不等于调用失败。 */
@Getter
@Builder
public class RecallChannelResult {

    public enum Channel {
        VECTOR,
        KEYWORD
    }

    public enum Status {
        SUCCESS,
        FAILED,
        TIMEOUT
    }

    public enum FailureStage {
        EMBEDDING,
        PG_VECTOR,
        ES,
        SUBMISSION,
        WAITING
    }

    private final Channel channel;

    private final Status status;

    private final List<RetrieveResult> candidates;

    private final FailureStage failureStage;

    private final String failureType;

    /** 从本次召回开始计时，包含排队时间，用于核对任务是否在期限内完成。 */
    private final long elapsedNanos;

    public long getElapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    }
}
