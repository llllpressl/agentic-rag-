package com.llll.agent.memory;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只构造新消息，提交由 Middleware 统一完成，异常不会留下部分裁剪。 */
@Component
@RequiredArgsConstructor
public class AgentContextTrimmer {

    private static final String OMITTED = "[历史工具结果已省略，如有需要请重新检索]";
    private static final int KEEP_RECENT_CYCLES = 2;

    private final AgentMemoryProperties memoryProperties;

    public List<Msg> trim(List<Msg> context) {
        int currentTurnStart = AgentContextChars.lastUserIndex(context);
        List<Msg> result = new ArrayList<>(context);
        if (currentTurnStart < 0) {
            return result;
        }
        List<Cycle> cycles = splitCycles(context);
        int kept = 0;
        for (int index = cycles.size() - 1; index >= 0; index--) {
            Cycle cycle = cycles.get(index);
            if (cycle.startIndex() >= currentTurnStart || !cycle.pendingIds().isEmpty()) {
                continue;
            }
            if (kept < KEEP_RECENT_CYCLES) {
                kept++;
                continue;
            }
            for (int messageIndex : cycle.resultIndexes()) {
                if (messageIndex < currentTurnStart) {
                    result.set(messageIndex, replaceOutputs(result.get(messageIndex), cycle));
                }
            }
        }
        return result;
    }

    /** 按调用 ID 追踪所有循环；并行结果、未完成及挂起结果都不会被误判为已闭合。 */
    private List<Cycle> splitCycles(List<Msg> context) {
        List<Cycle> cycles = new ArrayList<>();
        Map<String, Cycle> pending = new HashMap<>();
        for (int index = 0; index < context.size(); index++) {
            Msg message = context.get(index);
            List<ToolUseBlock> calls = message.getContentBlocks(ToolUseBlock.class);
            if (message.getRole() == MsgRole.ASSISTANT && !calls.isEmpty()) {
                Cycle cycle = Cycle.builder()
                        .startIndex(index)
                        .resultIndexes(new HashSet<>())
                        .pendingIds(new HashSet<>())
                        .toolNames(new HashMap<>())
                        .build();
                for (ToolUseBlock call : calls) {
                    if (call.getId() == null || pending.containsKey(call.getId())) {
                        // 异常 ID 无法可靠划分循环，本次全部放弃裁剪。
                        return List.of();
                    }
                    cycle.pendingIds().add(call.getId());
                    cycle.toolNames().put(call.getId(), call.getName());
                    pending.put(call.getId(), cycle);
                }
                cycles.add(cycle);
            }
            for (ToolResultBlock output : message.getContentBlocks(ToolResultBlock.class)) {
                Cycle cycle = pending.get(output.getId());
                if (cycle != null) {
                    cycle.resultIndexes().add(index);
                    if (output.getState() != ToolResultState.RUNNING && !output.isSuspended()) {
                        cycle.pendingIds().remove(output.getId());
                        pending.remove(output.getId());
                    }
                }
            }
        }
        return cycles;
    }

    private Msg replaceOutputs(Msg message, Cycle cycle) {
        List<ContentBlock> content = new ArrayList<>();
        boolean changed = false;
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ToolResultBlock output && canEvict(output, cycle)) {
                TextBlock placeholder = TextBlock.builder()
                        .text(OMITTED)
                        .build();
                content.add(ToolResultBlock.builder()
                        .id(output.getId())
                        .name(output.getName())
                        .output(placeholder)
                        .metadata(output.getMetadata())
                        .state(output.getState())
                        .build());
                changed = true;
            } else {
                content.add(block);
            }
        }
        return changed ? message.withContent(content) : message;
    }

    private boolean canEvict(ToolResultBlock output, Cycle cycle) {
        if (output.getName() == null || !memoryProperties.getEvictableTools().contains(output.getName())) {
            return false;
        }
        if (!output.getName().equals(cycle.toolNames().get(output.getId()))
                || output.getState() == ToolResultState.RUNNING || output.isSuspended()) {
            return false;
        }
        // 占位自身不大于占位长度，也自然排除了重复裁剪。
        return AgentContextChars.blocks(output.getOutput()) > OMITTED.length();
    }

    @Builder
    private record Cycle(int startIndex, Set<Integer> resultIndexes,
                         Set<String> pendingIds, Map<String, String> toolNames) {
    }
}
