package com.llll.agent.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.event.AgentEvent;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 一轮任务的执行句柄；停止时保留订阅，等待 Agent 完成协作中断。 */
@Slf4j
@Builder
public class AgentRunHandle {

    @Getter
    private final String taskId;
    @Getter
    private final String userId;
    @Getter
    private final String conversationId;

    private final ReActAgent agent;
    private final AgentRun<AgentEvent> agentRun;
    private final SseEmitter emitter;
    private final AgentStreamEventBridge eventBridge;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicReference<String> interruptReason = new AtomicReference<>();
    private final AtomicReference<Throwable> eventFailure = new AtomicReference<>();

    private Runnable onReleased;

    public void start(Runnable onReleased) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("同一轮 Agent 任务不能重复启动");
        }
        this.onReleased = onReleased;
        try {
            registerCallbacks();
            if (!eventBridge.sendStart()) {
                interrupt("client_disconnect");
            }
            agentRun.stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.boundedElastic())
                    .subscribe(this::handleEvent, this::finish, () -> finish(null));
        } catch (RuntimeException exception) {
            finish(exception);
        }
    }

    public void interrupt(String reason) {
        if (finished.get() || agentRun.status().isTerminal()) {
            return;
        }
        if (interruptReason.compareAndSet(null, reason)) {
            if (!agentRun.interrupt()) {
                // 检查状态后任务可能已经结束；未被框架接受的停止请求不覆盖正常结果。
                interruptReason.compareAndSet(reason, null);
            }
        }
    }

    private void registerCallbacks() {
        emitter.onTimeout(() -> {
            eventBridge.markConnectionClosed();
            interrupt("timeout");
        });
        emitter.onError(exception -> {
            eventBridge.markConnectionClosed();
            interrupt("client_disconnect");
        });
        emitter.onCompletion(() -> {
            eventBridge.markConnectionClosed();
            if (!finished.get()) {
                interrupt("client_disconnect");
            }
        });
    }

    private void handleEvent(AgentEvent event) {
        try {
            if (!eventBridge.handleEvent(event)) {
                interrupt("client_disconnect");
            }
        } catch (RuntimeException exception) {
            // 不把事件处理异常抛回订阅者，避免直接取消订阅而跳过框架中断收尾。
            eventFailure.compareAndSet(null, exception);
            log.error("处理 Agent 事件失败，taskId={}", taskId, exception);
            interrupt("event_error");
        }
    }

    private void finish(Throwable exception) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = exception;
        if (failure == null) {
            failure = eventFailure.get();
        }
        if (failure != null) {
            log.warn("Agent 本轮执行结束时存在异常，taskId={}", taskId, failure);
        }
        try {
            eventBridge.finish(interruptReason.get(), failure);
        } catch (RuntimeException finishException) {
            log.error("Agent 对话收尾失败，taskId={}", taskId, finishException);
            emitter.completeWithError(finishException);
        } finally {
            release();
        }
    }

    private void release() {
        try {
            emitter.complete();
        } finally {
            try {
                // Provider 当前每轮创建一个 Agent；完成状态保存后释放它的缓存和关闭钩子。
                agent.close();
            } finally {
                if (onReleased != null) {
                    onReleased.run();
                }
            }
        }
    }
}
