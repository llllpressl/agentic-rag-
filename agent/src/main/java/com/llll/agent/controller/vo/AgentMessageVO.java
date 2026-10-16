package com.llll.agent.controller.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentMessageVO {

    private String id;

    private String role;

    private String content;

    private String thinkingContent;

    private String lastMessageId;

    private String status;
}
