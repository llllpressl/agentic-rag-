package com.llll.agent.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 单例只保存依赖，本轮标志放入 RuntimeContext，不跨用户或会话共享。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentContextCompactionMiddleware implements MiddlewareBase {

    private static final String REASONING_STARTED = "agent.memory.reasoning-started";

    private final AgentMemoryProperties memoryProperties;
    private final AgentContextTrimmer trimmer;
    private final AgentContextCompactor compactor;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext runtimeContext, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (runtimeContext == null) {
                // 当前业务总会传入 RuntimeContext；兼容框架无显式上下文的调用入口。
                return next.apply(input);
            }
            runtimeContext.put(REASONING_STARTED, null);
            runtimeContext.put(AgentContextLimitException.class, null);
            return next.apply(input).concatWith(Flux.defer(() -> {
                // 2.0.4 的最大迭代收尾会将模型异常转为结果，仍需向业务层报告硬上限错误。
                AgentContextLimitException failure = runtimeContext.get(AgentContextLimitException.class);
                if (failure != null) {
                    return Flux.error(failure);
                }
                return Flux.empty();
            }));
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                       Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> dispatch(agent, runtimeContext, input, next));
    }

    private Flux<AgentEvent> dispatch(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                      Function<ReasoningInput, Flux<AgentEvent>> next) {
        boolean firstReasoning = !Boolean.TRUE.equals(runtimeContext.get(REASONING_STARTED));
        runtimeContext.put(REASONING_STARTED, true);
        AgentState state = RuntimeContext.resolveAgentState(runtimeContext, agent);
        if (state == null) {
            return proceed(input, runtimeContext, next);
        }
        List<Msg> context = state.contextMutable();
        int currentTurnStart = AgentContextChars.lastUserIndex(context);
        // 只在新用户消息后的第一次推理管理历史；恢复工具后和本轮后续推理只检查硬上限。
        if (!firstReasoning || currentTurnStart < 0 || currentTurnStart != context.size() - 1) {
            return proceed(input, runtimeContext, next);
        }
        long beforeChars = AgentContextChars.total(context);
        if (beforeChars < memoryProperties.resolveTrimTriggerChars()) {
            return proceed(input, runtimeContext, next);
        }
        boolean summarize = beforeChars >= memoryProperties.resolveCompactTriggerChars();
        if (summarize && !memoryProperties.isSummaryEnabled()) {
            return proceed(input, runtimeContext, next);
        }
        List<Msg> snapshot = new ArrayList<>(context);
        List<Msg> prefix = resolvePrefix(input.messages(), snapshot);
        if (prefix == null) {
            log.warn("上行消息与 AgentState 引用不一致，跳过上下文管理，sessionId={}", runtimeContext.getSessionId());
            return proceed(input, runtimeContext, next);
        }
        return Mono.fromCallable(() -> summarize ? compactor.compact(snapshot) : trimmer.trim(snapshot))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(exception -> {
                    // 80% 分支失败仍返回原历史，本次绝不降级到工具裁剪。
                    log.warn("上下文管理失败，保留原上下文，summary={}, sessionId={}",
                            summarize, runtimeContext.getSessionId(), exception);
                    return Mono.just(snapshot);
                })
                .flatMapMany(candidate -> {
                    if (!sameMessages(context, snapshot)) {
                        return Flux.error(new IllegalStateException("上下文在管理期间发生变化，停止本轮请求"));
                    }
                    ReasoningInput updated = rebuild(input, prefix, candidate);
                    checkLimit(updated.messages(), runtimeContext);
                    if (!sameMessages(candidate, snapshot)) {
                        // 候选消息、真实上行列表、硬上限均已校验，最后才提交工作状态。
                        context.clear();
                        context.addAll(candidate);
                        log.info("上下文管理完成，summary={}, chars={} -> {}, sessionId={}",
                                summarize, beforeChars, AgentContextChars.total(candidate), runtimeContext.getSessionId());
                    }
                    return next.apply(updated);
                });
    }

    /** 额外覆盖本轮工具增长和框架最大迭代收尾；此处始终不裁剪、不摘要。 */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext runtimeContext, ModelCallInput input,
                                       Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            checkLimit(input.messages(), runtimeContext);
            return next.apply(input);
        });
    }

    private Flux<AgentEvent> proceed(ReasoningInput input, RuntimeContext runtimeContext,
                                     Function<ReasoningInput, Flux<AgentEvent>> next) {
        checkLimit(input.messages(), runtimeContext);
        return next.apply(input);
    }

    private void checkLimit(List<Msg> messages, RuntimeContext runtimeContext) {
        long chars = AgentContextChars.total(messages);
        if (chars > memoryProperties.getContextWindowChars()) {
            AgentContextLimitException failure = new AgentContextLimitException(chars, memoryProperties.getContextWindowChars());
            runtimeContext.put(AgentContextLimitException.class, failure);
            throw failure;
        }
    }

    /** 2.0.4 的预推理事件复制列表但保留 Msg 引用；固定 System Prompt 位于前缀中。 */
    private List<Msg> resolvePrefix(List<Msg> messages, List<Msg> context) {
        int offset = messages.size() - context.size();
        if (offset < 0) {
            return null;
        }
        for (int index = 0; index < context.size(); index++) {
            if (messages.get(offset + index) != context.get(index)) {
                return null;
            }
        }
        return new ArrayList<>(messages.subList(0, offset));
    }

    private ReasoningInput rebuild(ReasoningInput input, List<Msg> prefix, List<Msg> context) {
        List<Msg> messages = new ArrayList<>(prefix);
        messages.addAll(context);
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    private boolean sameMessages(List<Msg> left, List<Msg> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index) != right.get(index)) {
                return false;
            }
        }
        return true;
    }
}
