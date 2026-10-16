package com.llll.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.llll.agent.dao.JsonbTypeHandler;
import lombok.*;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "t_agent_message", autoResultMap = true)
public class AgentMessageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    private String conversationId;

    private String role;

    private String content;

    private String thinkingContent;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private JsonNode block;

    private String lastMessageId;

    private String status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
}
