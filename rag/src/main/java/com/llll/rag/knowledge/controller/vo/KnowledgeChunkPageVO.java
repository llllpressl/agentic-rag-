package com.llll.rag.knowledge.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KnowledgeChunkPageVO {

    private List<KnowledgeChunkVO> records;

    private Long total;

    private Integer page;

    private Integer size;
}
