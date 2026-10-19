package com.llll.rag.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 单问题的候选 Chunk；某路未命中时，该路排名为 null。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrieveResult {

    private String id;

    private String content;

    private Integer vectorRank;

    private Integer bm25Rank;

    private Double rrfScore;

    private Double rerankScore;
}
