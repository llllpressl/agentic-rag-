package com.llll.agent.controller;

import com.llll.agent.controller.vo.AgentConversationPageVO;
import com.llll.agent.controller.vo.AgentMessageVO;
import com.llll.agent.service.AgentConversationService;
import com.llll.framwork.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class AgentConversationController {

    private final AgentConversationService conversationService;

    @GetMapping
    public Result<AgentConversationPageVO> listConversations(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "30") int pageSize) {
        return Result.success(conversationService.listConversations(pageNo, pageSize));
    }

    @GetMapping("/{conversationId}/messages")
    public Result<List<AgentMessageVO>> listMessages(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String afterMessageId) {
        return Result.success(conversationService.listMessages(conversationId, limit, afterMessageId));
    }
}
