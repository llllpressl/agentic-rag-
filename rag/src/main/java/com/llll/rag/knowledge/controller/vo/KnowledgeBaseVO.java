package com.llll.rag.knowledge.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class KnowledgeBaseVO {

    private String id;

    private String name;

    private String description;

    private String collection;

    private String embeddingModel;

    private Date createTime;

    private Date updateTime;

    private String createBy;

    private String updateBy;
}
