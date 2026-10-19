package com.llll.rag.knowledge.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KnowledgeDocVO {

    private String id;

    private String kbId;

    private String docName;

    private Integer chunkCount;

    private String fileUrl;

    private String fileType;

    private String mimeType;

    private Long fileSize;

    private Date createTime;

    private Date updateTime;

    private String createBy;

    private String updateBy;

    private String status;

    private Integer enabled;
}
