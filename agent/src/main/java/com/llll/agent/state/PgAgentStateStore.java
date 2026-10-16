package com.llll.agent.state;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llll.agent.dao.entity.AgentStateDO;
import com.llll.agent.dao.mapper.AgentStateMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;


/** 框架自动调用此存储；每次写入完整 AgentState，不按 token 写库。 */
@RequiredArgsConstructor
public class PgAgentStateStore implements AgentStateStore {

    private final AgentStateMapper agentStateMapper;
    private final ObjectMapper json = new ObjectMapper();

    /// 需要用到的时候再写重写，没有用到的时候不要提前写重写

    /** 以下为接口要求的抽象方法；版本控制等可选扩展继续使用框架默认实现。 */
    @Override
    public synchronized void save(String userId, String sessionId, String key, State value) {
        requireKey(key);
        if (value == null) {
            throw new IllegalArgumentException("状态值不能为空");
        }
        AgentStateDO state = getState(userId, sessionId);
        ObjectNode allContent = readContent(state);
        ObjectNode states = (ObjectNode) allContent.get("states");
        states.set(key, serializeState(value));
        saveContent(userId, sessionId, state, allContent);
    }

    @Override
    public synchronized void save(String userId, String sessionId, String key,
                                  List<? extends State> values) {
        requireKey(key);
        if (values == null) {
            throw new IllegalArgumentException("状态列表不能为空");
        }
        AgentStateDO state = getState(userId, sessionId);
        ObjectNode allContent = readContent(state);
        ArrayNode items = json.createArrayNode();
        for (State value : values) {
            if (value == null) {
                throw new IllegalArgumentException("状态列表不能包含空值");
            }
            items.add(serializeState(value));
        }
        ObjectNode lists = (ObjectNode) allContent.get("lists");
        lists.set(key, items);
        saveContent(userId, sessionId, state, allContent);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId,
                                            String key, Class<T> type) {
        requireKey(key);
        AgentStateDO state = getState(userId, sessionId);
        if (state == null) {
            return Optional.empty();
        }
        JsonNode value = readContent(state).get("states").get(key);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        // 框架 codec 负责 AgentState 内部 Msg、ContentBlock 等类型的恢复。
        T result = JsonUtils.getJsonCodec().fromJson(value.toString(), type);
        return Optional.of(result);
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId,
                                            String key, Class<T> itemType) {
        requireKey(key);
        AgentStateDO state = getState(userId, sessionId);
        List<T> result = new ArrayList<>();
        if (state == null) {
            return result;
        }
        JsonNode values = readContent(state).get("lists").get(key);
        if (values == null || values.isNull()) {
            return result;
        }
        if (!values.isArray()) {
            throw new IllegalStateException("Agent 状态列表格式不正确");
        }
        for (JsonNode value : values) {
            T item = JsonUtils.getJsonCodec().fromJson(value.toString(), itemType);
            result.add(item);
        }
        return result;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return getState(userId, sessionId) != null;
    }

    @Override
    public synchronized void delete(String userId, String sessionId) {
        requireIdentity(userId, sessionId);
        // AgentStateDO 已声明 @TableLogic，此处执行逻辑删除。
        agentStateMapper.delete(new LambdaQueryWrapper<AgentStateDO>()
                .eq(AgentStateDO::getUserId, userId)
                .eq(AgentStateDO::getSessionId, sessionId));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        List<AgentStateDO> states = agentStateMapper.selectList(
                new LambdaQueryWrapper<AgentStateDO>()
                        .eq(AgentStateDO::getUserId, userId)
                        .eq(AgentStateDO::getDeleted, 0));
        Set<String> sessionIds = new LinkedHashSet<>();
        for (AgentStateDO state : states) {
            sessionIds.add(state.getSessionId());
        }
        return sessionIds;
    }

    private AgentStateDO getState(String userId, String sessionId) {
        requireIdentity(userId, sessionId);
        return agentStateMapper.selectOne(new LambdaQueryWrapper<AgentStateDO>()
                .eq(AgentStateDO::getUserId, userId)
                .eq(AgentStateDO::getSessionId, sessionId)
                .eq(AgentStateDO::getDeleted, 0));
    }

    private ObjectNode createEmptyContent() {
        ObjectNode allContent = json.createObjectNode();
        allContent.put("formatVersion", 1);
        allContent.putObject("states");
        allContent.putObject("lists");
        return allContent;
    }

    private ObjectNode readContent(AgentStateDO state) {
        if (state == null) {
            return createEmptyContent();
        }
        JsonNode stored = state.getAllContent();
        if (stored == null || !stored.isObject()
                || !stored.path("states").isObject()
                || !stored.path("lists").isObject()) {
            throw new IllegalStateException("Agent 状态文档格式不正确");
        }
        return ((ObjectNode) stored).deepCopy();
    }

    private JsonNode serializeState(State value) {
        try {
            String stateJson = JsonUtils.getJsonCodec().toJson(value);
            return json.readTree(stateJson);
        } catch (IOException exception) {
            throw new IllegalStateException("Agent 状态序列化失败", exception);
        }
    }

    private void saveContent(String userId, String sessionId, AgentStateDO state,
                             ObjectNode allContent) {
        Date now = new Date();
        if (state == null) {
            AgentStateDO newState = AgentStateDO.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .allContent(allContent)
                    .createTime(now)
                    .updateTime(now)
                    .deleted(0)
                    .build();
            if (agentStateMapper.insert(newState) != 1) {
                throw new IllegalStateException("Agent 状态保存失败");
            }
            return;
        }
        AgentStateDO update = AgentStateDO.builder()
                .id(state.getId())
                .allContent(allContent)
                .updateTime(now)
                .build();
        if (agentStateMapper.updateById(update) != 1) {
            throw new IllegalStateException("Agent 状态更新失败");
        }
        // TODO 同会话并行任务、跨节点的旧状态覆盖需要单独协调，本阶段不提供该保证。
    }

    private void requireIdentity(String userId, String sessionId) {
        // 当前业务表要求明确用户，不提供框架可选的匿名状态命名空间。
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("用户 ID 和会话 ID 不能为空");
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("状态 key 不能为空");
        }
    }
}
