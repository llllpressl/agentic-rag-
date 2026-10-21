package com.llll.rag.search.routing.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class RouteDecision {

    private final RouteMode mode;

    /** 只有 SINGLE、MULTI 包含模型筛选出的知识库 ID。 */
    private final List<String> kbIds;

    private final RetrievalScope scope;

    /** 本次请求的可用库快照，兜底不能越过此范围。 */
    private final RetrievalScope globalScope;

    private final int candidateKbCount;

    private final long jevDurationMs;

    private final boolean routingFallback;
}
