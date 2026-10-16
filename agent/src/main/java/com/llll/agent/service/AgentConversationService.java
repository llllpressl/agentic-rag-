package com.llll.agent.service;

import com.llll.agent.controller.vo.AgentConversationPageVO;
import com.llll.agent.controller.vo.AgentMessageVO;

import java.util.List;

public interface AgentConversationService {

    String addConversation(String userId, String conversationId, String query);

    AgentConversationPageVO listConversations(int pageNo, int pageSize);

    List<AgentMessageVO> listMessages(String conversationId, int limit, String afterMessageId);
}
