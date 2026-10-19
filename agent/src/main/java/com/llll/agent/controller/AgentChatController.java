package com.llll.agent.controller;

import com.llll.agent.controller.request.ConfirmRequest;
import com.llll.agent.config.AgentProperties;
import com.llll.agent.service.AgentChatService;
import com.llll.framwork.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentProperties agentProperties;

    /** 支持 GET 和 POST 表单，避免长问题受 URL 长度限制。 */
    @RequestMapping(value = "/api/agent/chat", method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startChat(@RequestParam String query,
                                @RequestParam(required = false) String conversationId){
        SseEmitter sseEmitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        agentChatService.streamChat(query, conversationId, sseEmitter);
        return sseEmitter;
    }

    @PostMapping(value = "/api/agent/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirm(@RequestBody ConfirmRequest confirmRequest){
        SseEmitter sseEmitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        agentChatService.confirm(confirmRequest, sseEmitter);
        return sseEmitter;
    }

    @PostMapping("/api/agent/stop")
    public Result<Void> stop(@RequestParam String taskId){
        agentChatService.stopChat(taskId);
        return Result.success();
    }
}
