package com.llll.rag.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@TableName("t_knowledge_base")
public class KnowledgeBaseDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String name;

    private String description;

    private String collection;

    private String embeddingModel;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    private String createBy;

    private String updateBy;

    @TableLogic
    private Integer deleted;
}
