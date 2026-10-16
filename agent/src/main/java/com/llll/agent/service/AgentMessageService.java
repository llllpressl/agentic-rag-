package com.llll.agent.service;

import com.llll.agent.dao.entity.AgentMessageDO;

public interface AgentMessageService {

    String addUserMessage(String userId, String conversationId, String query);

    String addAssistantMessage(AgentMessageDO message);

}
