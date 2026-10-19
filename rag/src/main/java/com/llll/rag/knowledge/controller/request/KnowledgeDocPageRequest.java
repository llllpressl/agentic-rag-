package com.llll.rag.knowledge.controller.request;

import lombok.Data;

@Data
public class KnowledgeDocPageRequest {

    private String kbId;

    private String docName;

    private Integer pageNo = 1;

    private Integer pageSize = 20;
}
