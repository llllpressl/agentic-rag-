package com.llll.agent.runtime;

import com.llll.agent.concurrency.AgentRunLease;
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
    private final AgentRunLease runLease;

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
        // 先准备释放回调、再移交锁、最后订阅，不依赖异步任务结束的快慢。
        runLease.transferToRunHandle();
        try {
            registerCallbacks();
            if (!eventBridge.sendStart()) {
                interrupt("client_disconnect");
            }
            agentRun.stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.boundedElastic())
                    .subscribe(this::handleEvent, this::finish, () -> finish(null));
        } catch (RuntimeException | Error exception) {
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
        Throwable finishFailure = null;
        try {
            // 框架负责流终止前的状态保存及失败处理；这里只持久化 assistant 并准备终态。
            eventBridge.finish(interruptReason.get(), failure);
        } catch (RuntimeException | Error finishException) {
            log.error("Agent 对话收尾失败，taskId={}", taskId, finishException);
            finishFailure = finishException;
        } finally {
            release(finishFailure);
        }
    }

    private void release(Throwable finishFailure) {
        Throwable cleanupFailure = finishFailure;
        try {
            // 只清理本轮 Agent 缓存和关闭钩子，不删除持久化会话状态。
            agent.close();
        } catch (RuntimeException | Error closeException) {
            cleanupFailure = closeException;
            log.error("关闭 Agent 资源失败，taskId={}", taskId, closeException);
        }
        try {
            if (onReleased != null) {
                onReleased.run();
            }
        } catch (RuntimeException | Error removeException) {
            cleanupFailure = removeException;
            log.error("移除 Agent 活动任务失败，taskId={}", taskId, removeException);
        }
        if (!runLease.release()) {
            cleanupFailure = new IllegalStateException("Agent 锁释放失败，请查看并发控制日志");
        }
        try {
            // done/error 也等待清理和解锁结束后发送，协议字段保持不变。
            eventBridge.sendFinish(cleanupFailure);
        } catch (RuntimeException | Error sendException) {
            cleanupFailure = sendException;
            log.error("发送 Agent 终态事件失败，taskId={}", taskId, sendException);
        }
        try {
            if (cleanupFailure == null) {
                emitter.complete();
            } else {
                emitter.completeWithError(cleanupFailure);
            }
        } catch (RuntimeException | Error completeException) {
            log.warn("结束 SSE 连接失败，taskId={}", taskId, completeException);
        }
    }
}
