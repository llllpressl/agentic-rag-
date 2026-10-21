package com.llll.rag.search.routing.model;

import lombok.Builder;
import lombok.Getter;

/** 独立的 Noul 判断概率，不进行知识库之间的归一化。 */
@Getter
@Builder
public class KbScore {

    private final String kbId;

    private final double probability;
}
