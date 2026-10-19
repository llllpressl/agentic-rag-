package com.llll.rag.knowledge.controller.request;

import lombok.Data;

@Data
public class KnowledgeChunkPageRequest {

    private Integer pageNo = 1;

    private Integer pageSize = 20;
}
