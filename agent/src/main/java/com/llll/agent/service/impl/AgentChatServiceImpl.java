package com.llll.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.llll.agent.config.ReActAgentProvider;
import com.llll.agent.controller.request.ConfirmRequest;
import com.llll.agent.runtime.AgentRunHandle;
import com.llll.agent.runtime.AgentStreamEventBridge;
import com.llll.agent.service.AgentChatService;
import com.llll.agent.service.AgentConversationService;
import com.llll.agent.service.AgentMessageService;
import com.llll.framwork.context.UserContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private final ReActAgentProvider reActAgentProvider;
    private final AgentConversationService conversationService;
    private final AgentMessageService messageService;
    private final TransactionTemplate transactionTemplate;

    private final ConcurrentHashMap<String, AgentRunHandle> runningTasks = new ConcurrentHashMap<>();

    @Override
    public void streamChat(String query, String conversationId, SseEmitter sseEmitter) {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户身份");
        }
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        if (conversationId != null && (conversationId.isBlank() || conversationId.length() > 36)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话 ID 不合法");
        }
        conversationId = conversationId == null ? IdWorker.get32UUID() : conversationId;
        String taskId = IdWorker.get32UUID();// 用跟id一样长度的字段，雪花id还是啥
        // TODO 后续使用 taskId 在 Redis 中控制并发，并记录任务存活状态。
        readyStream(query, conversationId, taskId, userId, sseEmitter);
    }

    private void readyStream(String query, String conversationId, String taskId,
                             String userId, SseEmitter sseEmitter) {
        ReActAgent agent = reActAgentProvider.getAgent();

        /**
         * 原流程：addConversation 后直接 addUserMessage，再构造 ExecuteParam 并 launchStream。
         * 现在把会话准备和 user 消息入库放在短事务中；事务结束后才启动模型。
         */
        try {
            ExecuteParam executeParam = transactionTemplate.execute(transactionStatus -> {
                String title = conversationService.addConversation(userId, conversationId, query);
                String userMessageId = messageService.addUserMessage(userId, conversationId, query);
                return ExecuteParam.builder()
                        .emitter(sseEmitter)
                        .userId(userId)
                        .conversationId(conversationId)
                        .taskId(taskId)
                        .title(title)
                        .lastMessageId(userMessageId)
                        .build();
            });
            if (executeParam == null) {
                throw new IllegalStateException("对话准备失败");
            }
            launchStream(new UserMessage(query), agent, executeParam);
        } catch (RuntimeException exception) {
            agent.close();
            throw exception;
        }
    }
    @Builder
    private record ExecuteParam(SseEmitter emitter , String userId, String conversationId, String taskId, String title, String lastMessageId){
    }



    private void launchStream(Msg input, ReActAgent agent, ExecuteParam executeParam) {
        /** 原占位：RuntimeContext、AgentRun、SSE 生命周期、事件订阅和统一收尾待实现。 */
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(executeParam.userId())
                .sessionId(executeParam.conversationId())
                .build();
        AgentRun<AgentEvent> agentRun = agent.prepareRun(List.of(input), runtimeContext);
        AgentStreamEventBridge eventBridge = AgentStreamEventBridge.builder()
                .emitter(executeParam.emitter())
                .messageService(messageService)
                .userId(executeParam.userId())
                .conversationId(executeParam.conversationId())
                .taskId(executeParam.taskId())
                .title(executeParam.title())
                .lastMessageId(executeParam.lastMessageId())
                .build();
        AgentRunHandle handle = AgentRunHandle.builder()
                .taskId(executeParam.taskId())
                .userId(executeParam.userId())
                .conversationId(executeParam.conversationId())
                .agent(agent)
                .agentRun(agentRun)
                .emitter(executeParam.emitter())
                .eventBridge(eventBridge)
                .build();
        if (runningTasks.putIfAbsent(executeParam.taskId(), handle) != null) {
            throw new IllegalStateException("Agent 任务 ID 重复");
        }
        handle.start(() -> runningTasks.remove(executeParam.taskId(), handle));
    }



    @Override
    public void confirm(ConfirmRequest confirmRequest, SseEmitter sseEmitter) {
        // TODO 后续接入工具确认；当前明确拒绝，避免返回一个永不结束的 SSE 连接。
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "暂未支持工具确认");
    }

    @Override
    public void stopChat(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务 ID 不能为空");
        }
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少当前用户身份");
        }
        AgentRunHandle handle = runningTasks.get(taskId);
        if (handle == null || !userId.equals(handle.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前用户的活动任务");
        }
        handle.interrupt("user_stop");
    }
}
