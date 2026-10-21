package com.llll.rag.search.model;

import lombok.Builder;
import lombok.Getter;

/** 一轮双路召回及已完成的 Query Embedding 快照，不跨请求共享。 */
@Getter
@Builder
public class RecallRoundResult {

    private final RecallChannelResult vectorResult;

    private final RecallChannelResult keywordResult;

    private final float[] queryVector;

    public boolean isDegraded() {
        return vectorResult.getStatus() != RecallChannelResult.Status.SUCCESS
                || keywordResult.getStatus() != RecallChannelResult.Status.SUCCESS;
    }
}
