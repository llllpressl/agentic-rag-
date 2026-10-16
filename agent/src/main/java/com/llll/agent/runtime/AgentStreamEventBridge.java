package com.llll.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llll.agent.controller.vo.AgentSseEvent;
import com.llll.agent.dao.entity.AgentMessageDO;
import com.llll.agent.service.AgentMessageService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.util.JsonUtils;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每轮创建一个桥接对象；只处理事件、SSE 和聊天记录，不负责启动或停止 Agent。 */
@Slf4j
@Builder
public class AgentStreamEventBridge {

    private final SseEmitter emitter;
    private final AgentMessageService messageService;
    private final String userId;
    private final String conversationId;
    private final String taskId;
    private final String title;
    private final String lastMessageId;

    private final ObjectMapper json = new ObjectMapper();
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder thinkingContent = new StringBuilder();
    private final AtomicBoolean connectionClosed = new AtomicBoolean();

    private Msg resultMessage;

    public boolean sendStart() {
        AgentSseEvent event = AgentSseEvent.builder()
                .type("start")
                .conversationId(conversationId)
                .taskId(taskId)
                .title(title)
                .userMessageId(lastMessageId)
                .build();
        return sendEvent(event);
    }

    public boolean handleEvent(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent textEvent) {
            String delta = textEvent.getDelta();
            if (delta == null || delta.isEmpty()) {
                return !connectionClosed.get();
            }
            content.append(delta);
            return sendDelta("text.delta", delta);
        }
        if (event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
            String delta = thinkingEvent.getDelta();
            if (delta == null || delta.isEmpty()) {
                return !connectionClosed.get();
            }
            thinkingContent.append(delta);
            return sendDelta("thinking.delta", delta);
        }
        if (event instanceof AgentResultEvent resultEvent) {
            resultMessage = resultEvent.getResult();
        }
        return !connectionClosed.get();
    }

    public void markConnectionClosed() {
        connectionClosed.set(true);
    }

    public void finish(String interruptReason, Throwable exception) {
        AgentMessageDO message = buildAssistantMessage(interruptReason, exception);
        String assistantMessageId;
        try {
            assistantMessageId = messageService.addAssistantMessage(message);
        } catch (RuntimeException persistenceException) {
            log.error("保存 assistant 消息失败，taskId={}", taskId, persistenceException);
            sendError("persistence_error", "聊天记录保存失败", null, null);
            return;
        }

        String reason = message.getBlock().path("reason").asText(null);
        // 尚未开始的任务被停止时，框架用 CancellationException 结束，而不是返回恢复消息。
        boolean stoppedBeforeRunning = interruptReason != null
                && exception instanceof CancellationException;
        if ((exception != null && !stoppedBeforeRunning) || "missing_result".equals(reason)) {
            sendError(reason, "本轮对话执行失败", assistantMessageId, message.getStatus());
            return;
        }
        AgentSseEvent event = AgentSseEvent.builder()
                .type("done")
                .conversationId(conversationId)
                .taskId(taskId)
                .assistantMessageId(assistantMessageId)
                .status(message.getStatus())
                .reason(reason)
                .build();
        sendEvent(event);
    }

    private boolean sendDelta(String type, String delta) {
        AgentSseEvent event = AgentSseEvent.builder()
                .type(type)
                .conversationId(conversationId)
                .taskId(taskId)
                .delta(delta)
                .build();
        return sendEvent(event);
    }

    private void sendError(String reason, String message,
                           String assistantMessageId, String status) {
        AgentSseEvent event = AgentSseEvent.builder()
                .type("error")
                .conversationId(conversationId)
                .taskId(taskId)
                .assistantMessageId(assistantMessageId)
                .status(status)
                .reason(reason)
                .message(message)
                .build();
        sendEvent(event);
    }

    private boolean sendEvent(AgentSseEvent event) {
        if (connectionClosed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType())
                    .data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException exception) {
            markConnectionClosed();
            log.debug("SSE 连接已不可写，taskId={}", taskId, exception);
            return false;
        }
    }

    private AgentMessageDO buildAssistantMessage(String interruptReason, Throwable exception) {
        String reason = interruptReason;
        if (reason == null && exception != null) {
            reason = "execution_error";
        }
        if (reason == null && resultMessage == null) {
            reason = "missing_result";
        }
        if (reason == null && resultMessage != null) {
            GenerateReason generateReason = resultMessage.getGenerateReason();
            if (generateReason != GenerateReason.MODEL_STOP
                    && generateReason != GenerateReason.STRUCTURED_OUTPUT
                    && generateReason != GenerateReason.TOOL_RETURN_DIRECT) {
                reason = "agent_" + generateReason.name().toLowerCase(Locale.ROOT);
            }
        }

        String status = "normal";
        String answer = content.toString();
        String thinking = thinkingContent.toString();
        if (reason != null) {
            status = "interrupted";
        } else {
            answer = resultMessage.getTextContent();
            StringBuilder finalThinking = new StringBuilder();
            for (ContentBlock block : resultMessage.getContent()) {
                if (block instanceof ThinkingBlock thinkingBlock) {
                    if (thinkingBlock.getThinking() != null) {
                        finalThinking.append(thinkingBlock.getThinking());
                    }
                }
            }
            if (!finalThinking.isEmpty()) {
                thinking = finalThinking.toString();
            }
        }

        ObjectNode block = json.createObjectNode();
        block.put("taskId", taskId);
        if (reason != null) {
            block.put("reason", reason);
        }
        if (exception != null) {
            block.put("errorType", exception.getClass().getSimpleName());
        }
        if (resultMessage != null) {
            block.put("generateReason", resultMessage.getGenerateReason().name());
            block.set("content", serializeContent(resultMessage));
        }
        Date now = new Date();
        return AgentMessageDO.builder()
                .userId(userId)
                .conversationId(conversationId)
                .role("assistant")
                .content(answer)
                .thinkingContent(thinking)
                .block(block)
                .lastMessageId(lastMessageId)
                .status(status)
                .createTime(now)
                .updateTime(now)
                .deleted(0)
                .build();
    }

    private JsonNode serializeContent(Msg message) {
        try {
            String contentJson = JsonUtils.getJsonCodec().toJson(message.getContent());
            return json.readTree(contentJson);
        } catch (IOException exception) {
            throw new IllegalStateException("Agent 内容块转换失败", exception);
        }
    }
}
