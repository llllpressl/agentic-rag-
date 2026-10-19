package com.llll.rag.knowledge.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KnowledgeBasePageVO {

    private List<KnowledgeBaseVO> records;

    private Long total;

    private Integer page;

    private Integer size;
}
