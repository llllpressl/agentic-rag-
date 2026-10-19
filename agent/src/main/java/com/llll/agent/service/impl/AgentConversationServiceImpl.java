package com.llll.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llll.agent.controller.vo.AgentConversationPageVO;
import com.llll.agent.controller.vo.AgentConversationVO;
import com.llll.agent.controller.vo.AgentMessageVO;
import com.llll.agent.dao.entity.AgentConversationDO;
import com.llll.agent.dao.entity.AgentMessageDO;
import com.llll.agent.dao.mapper.AgentConversationMapper;
import com.llll.agent.dao.mapper.AgentMessageMapper;
import com.llll.agent.service.AgentConversationService;
import com.llll.framwork.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentConversationServiceImpl implements AgentConversationService {

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    @Override
    public String addConversation(String userId, String conversationId, String query) {
        AgentConversationDO agentConversationDO = hasConversation(userId, conversationId);
        if (agentConversationDO != null){
            agentConversationDO.setUpdateTime(new Date());
            conversationMapper.updateById(agentConversationDO);
            return agentConversationDO.getTitle();
        }
        return createConversation(userId, conversationId, query);
    }
    private String createConversation(String userId, String conversationId, String query) {
        String title = StrUtil.sub(StrUtil.emptyIfNull(query).trim(), 0, 20);
        AgentConversationDO agentConversationDO = AgentConversationDO.builder()
                .userId(userId)
                .conversationId(conversationId)
                .title(title)
                .createTime(new Date())
                .updateTime(new Date())
                .deleted(0)
                .build();
        conversationMapper.insert(agentConversationDO);
        return title;
    }
    public AgentConversationDO hasConversation(String userId, String conversationId){
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversationDO>()
                        .eq(AgentConversationDO::getUserId, userId)
                        .eq(AgentConversationDO::getConversationId, conversationId)
                        .eq(AgentConversationDO::getDeleted, 0));
    }

    @Override
    public AgentConversationPageVO listConversations(int pageNo, int pageSize) {
        String userId = requireUserId();
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码需大于 0，每页数量需为 1 至 100");
        }
        Long total = conversationMapper.selectCount(new LambdaQueryWrapper<AgentConversationDO>()
                .eq(AgentConversationDO::getUserId, userId)
                .eq(AgentConversationDO::getDeleted, 0));
        List<AgentConversationVO> records = new ArrayList<>();
        long offset = (long) (pageNo - 1) * pageSize;
        if (offset < total) {
            // 当前未注册分页插件；LIMIT/OFFSET 只拼接已经校验的数字。
            List<AgentConversationDO> conversations = conversationMapper.selectList(
                    new LambdaQueryWrapper<AgentConversationDO>()
                            .eq(AgentConversationDO::getUserId, userId)
                            .eq(AgentConversationDO::getDeleted, 0)
                            .orderByDesc(AgentConversationDO::getUpdateTime)
                            .orderByDesc(AgentConversationDO::getId)
                            .last("LIMIT " + pageSize + " OFFSET " + offset));
            for (AgentConversationDO conversation : conversations) {
                AgentConversationVO record = AgentConversationVO.builder()
                        .conversationId(conversation.getConversationId())
                        .title(conversation.getTitle())
                        .createTime(conversation.getCreateTime())
                        .updateTime(conversation.getUpdateTime())
                        .build();
                records.add(record);
            }
        }
        return AgentConversationPageVO.builder()
                .records(records)
                .total(total)
                .page(pageNo)
                .size(pageSize)
                .build();
    }

    @Override
    public List<AgentMessageVO> listMessages(String conversationId, int limit, String afterMessageId) {
        String userId = requireUserId();
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每次查询轮数需为 1 至 100");
        }
        if (hasConversation(userId, conversationId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前用户的会话");
        }
        LambdaQueryWrapper<AgentMessageDO> questionQuery = new LambdaQueryWrapper<AgentMessageDO>()
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getRole, "user")
                .eq(AgentMessageDO::getDeleted, 0);
        if (afterMessageId != null && !afterMessageId.isBlank()) {
            AgentMessageDO cursor = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessageDO>()
                    .eq(AgentMessageDO::getId, afterMessageId)
                    .eq(AgentMessageDO::getUserId, userId)
                    .eq(AgentMessageDO::getConversationId, conversationId)
                    .eq(AgentMessageDO::getRole, "user")
                    .eq(AgentMessageDO::getDeleted, 0));
            if (cursor == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息游标不属于当前会话");
            }
            // 时间相同时用消息 ID 继续定位，避免漏掉同一毫秒内写入的下一条问题。
            questionQuery.and(next -> next
                    .gt(AgentMessageDO::getCreateTime, cursor.getCreateTime())
                    .or(sameTime -> sameTime
                            .eq(AgentMessageDO::getCreateTime, cursor.getCreateTime())
                            .gt(AgentMessageDO::getId, cursor.getId())));
        }
        questionQuery.orderByAsc(AgentMessageDO::getCreateTime)
                .orderByAsc(AgentMessageDO::getId)
                .last("LIMIT " + limit);
        List<AgentMessageDO> questions = messageMapper.selectList(questionQuery);
        List<AgentMessageVO> result = new ArrayList<>();
        if (questions.isEmpty()) {
            return result;
        }
        List<String> questionIds = new ArrayList<>();
        for (AgentMessageDO question : questions) {
            questionIds.add(question.getId());
        }
        List<AgentMessageDO> replies = messageMapper.selectList(new LambdaQueryWrapper<AgentMessageDO>()
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getRole, "assistant")
                .eq(AgentMessageDO::getDeleted, 0)
                .in(AgentMessageDO::getLastMessageId, questionIds));
        Map<String, AgentMessageDO> replyByQuestion = new HashMap<>();
        for (AgentMessageDO reply : replies) {
            replyByQuestion.put(reply.getLastMessageId(), reply);
        }
        for (AgentMessageDO question : questions) {
            result.add(toMessageVO(question));
            AgentMessageDO reply = replyByQuestion.get(question.getId());
            if (reply != null) {
                result.add(toMessageVO(reply));
            }
        }
        return result;
    }

    private AgentMessageVO toMessageVO(AgentMessageDO message) {
        return AgentMessageVO.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .lastMessageId(message.getLastMessageId())
                .status(message.getStatus())
                .build();
    }

    private String requireUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户身份");
        }
        return userId;
    }
}
