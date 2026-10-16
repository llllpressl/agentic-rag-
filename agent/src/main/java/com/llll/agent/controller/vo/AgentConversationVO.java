package com.llll.agent.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class AgentConversationVO {

    private String conversationId;

    private String title;

    private Date createTime;

    private Date updateTime;
}
