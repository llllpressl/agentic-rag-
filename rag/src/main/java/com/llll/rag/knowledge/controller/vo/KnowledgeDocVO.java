package com.llll.rag.knowledge.controller.vo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
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
