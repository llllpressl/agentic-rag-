package com.llll.agent.controller.vo;

import lombok.Builder;
import lombok.Data;

/** 一轮对话发给前端的 SSE 数据；不同事件只填写需要的字段。 */
@Data
@Builder
public class AgentSseEvent {

    private String type;

    private String conversationId;

    private String taskId;

    private String title;

    private String userMessageId;

    private String assistantMessageId;

    private String delta;

    private String status;

    private String reason;

    private String message;
}
