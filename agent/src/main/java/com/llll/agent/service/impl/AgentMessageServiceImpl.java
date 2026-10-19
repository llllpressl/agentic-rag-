package com.llll.agent.service.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.llll.agent.dao.entity.AgentMessageDO;
import com.llll.agent.dao.mapper.AgentMessageMapper;
import com.llll.agent.service.AgentMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class AgentMessageServiceImpl implements AgentMessageService {

    private final AgentMessageMapper agentMessageMapper;

    @Override
    public String addUserMessage(String userId, String conversationId, String query) {
        AgentMessageDO agentMessageDO = AgentMessageDO.builder()
                .userId(userId)
                .conversationId(conversationId)
                .role("user")
                .content(query)
                .thinkingContent(null)
                .block(JsonNodeFactory.instance.objectNode())
                .lastMessageId(null)
                .status("normal")
                .createTime(new Date())
                .updateTime(new Date())
                .deleted(0)
                .build();
        agentMessageMapper.insert(agentMessageDO);
        return agentMessageDO.getId();
    }

    // TODO 为什么不在service里创建实体类？
    @Override
    public String addAssistantMessage(AgentMessageDO message) {
        if (!"assistant".equals(message.getRole())) {
            throw new IllegalArgumentException("只能通过此方法保存 assistant 消息");
        }
        if (agentMessageMapper.insert(message) != 1) {
            throw new IllegalStateException("assistant 消息保存失败");
        }
        return message.getId();
    }
}
