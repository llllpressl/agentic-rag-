package com.llll.rag.knowledge.controller.request;

import lombok.Data;

@Data
public class KnowledgeBasePageRequest {

    private String name;

    private Integer pageNo = 1;

    private Integer pageSize = 20;
}
