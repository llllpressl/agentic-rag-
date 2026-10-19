package com.llll.agent.service;

import com.llll.agent.controller.request.ConfirmRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentChatService {

    void streamChat(String query, String conversationId, SseEmitter sseEmitter);

    void confirm(ConfirmRequest confirmRequest, SseEmitter sseEmitter);

    void stopChat(String taskId);
}
