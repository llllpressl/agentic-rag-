package com.llll.rag.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.llll.rag.knowledge.dao.handler.VectorTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.JdbcType;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@TableName(value = "t_knowledge_chunk", autoResultMap = true)
public class KnowledgeChunkDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String kbId;

    private String collection;

    private String docId;

    private Integer chunkIndex;

    private String content;

    @TableField(jdbcType = JdbcType.OTHER, typeHandler = VectorTypeHandler.class)
    private float[] embedding;

    private Integer charCount;

    private Integer tokenCount;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    private String createBy;

    private String updateBy;

    private Integer enabled;

    @TableLogic
    private Integer deleted;
}
