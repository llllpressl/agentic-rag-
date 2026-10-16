package com.llll.agent.service;

import com.llll.agent.controller.request.ConfirmRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentChatService {

    void streamChat(String query, String conversationId, SseEmitter sseEmitter);

    void confirm(ConfirmRequest confirmRequest, SseEmitter sseEmitter);

    /** 原实现：default void stopChat(String taskId) {}，空实现会掩盖停止能力未实现的问题。 */
    void stopChat(String taskId);
}
