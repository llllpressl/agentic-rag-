package com.llll.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_agent_conversation")
public class AgentConversationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    private String conversationId;

    private String title;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
}
