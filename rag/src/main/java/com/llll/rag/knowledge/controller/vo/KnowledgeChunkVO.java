package com.llll.rag.knowledge.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class KnowledgeChunkVO {

    private String id;

    private String kbId;

    private String docId;

    private Integer chunkIndex;

    private String content;

    private Integer charCount;

    private Integer tokenCount;

    private Date createTime;

    private Date updateTime;

    private String createBy;

    private String updateBy;

    private Integer enabled;
}
