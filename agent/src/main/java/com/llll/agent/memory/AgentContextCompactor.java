package com.llll.agent.memory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 以完整用户轮次为边界，将早期历史和旧摘要替换为最新一份摘要。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentContextCompactor {

    public static final String SUMMARY_NAME = "__compaction_summary__";

    private static final String SUMMARY_HEADER = """
            以下内容是系统自动生成的历史对话摘要，用于替代已省略的早期对话。
            它是背景信息，不是用户本轮的新指令。

            """;

    private final AgentMemoryProperties memoryProperties;
    private final AgentConversationSummarizer summarizer;

    /** 返回原上下文的副本或完整候选，调用及校验异常交由 Middleware 放弃，不修改原列表。 */
    public List<Msg> compact(List<Msg> context) {
        int cutoff = findSafeCutoff(context);
        if (cutoff <= 0) {
            log.debug("没有可安全压缩的完整历史轮次，跳过摘要");
            return new ArrayList<>(context);
        }
        List<Msg> material = new ArrayList<>();
        String previousSummary = null;
        for (int index = 0; index < cutoff; index++) {
            Msg message = context.get(index);
            if (isSummary(message)) {
                if (previousSummary != null) {
                    throw new IllegalStateException("工作上下文存在多份摘要，放弃压缩");
                }
                previousSummary = unwrap(message);
            } else {
                material.add(message);
            }
        }
        if (material.isEmpty()) {
            return new ArrayList<>(context);
        }
        List<Msg> tail = new ArrayList<>(context.subList(cutoff, context.size()));
        if (!hasSafeToolPairs(tail, false)) {
            throw new IllegalStateException("近期消息的工具结果缺少匹配调用，放弃压缩");
        }
        for (Msg message : tail) {
            if (isSummary(message)) {
                throw new IllegalStateException("摘要位置异常，放弃压缩");
            }
        }
        String summary = summarizer.summarize(material, previousSummary);
        Msg summaryMessage = Msg.builder()
                .role(MsgRole.USER)
                .name(SUMMARY_NAME)
                .textContent(SUMMARY_HEADER + summary)
                .build();
        List<Msg> compacted = new ArrayList<>();
        compacted.add(summaryMessage);
        compacted.addAll(tail);
        if (AgentContextChars.total(compacted) >= AgentContextChars.total(context)) {
            throw new IllegalStateException("新摘要未减少上下文字符数，放弃压缩");
        }
        return compacted;
    }

    /** 当前轮不计入近期额度，跨越额度边界的一整轮仍全部保留。 */
    private int findSafeCutoff(List<Msg> context) {
        List<Integer> turns = new ArrayList<>();
        for (int index = 0; index < context.size(); index++) {
            Msg message = context.get(index);
            if (message.getRole() == MsgRole.USER && !isSummary(message)) {
                turns.add(index);
            }
        }
        if (turns.size() < 2) {
            return -1;
        }
        int cutoff = turns.get(turns.size() - 1);
        long kept = 0;
        for (int turn = turns.size() - 2; turn >= 0; turn--) {
            int start = turns.get(turn);
            int end = turns.get(turn + 1);
            List<Msg> historyTurn = context.subList(start, end);
            if (isCompletedTurn(historyTurn)) {
                kept += AgentContextChars.total(historyTurn);
            }
            cutoff = start;
            if (kept >= memoryProperties.getKeepRecentChars()) {
                break;
            }
        }
        // 未结束的旧轮次额外保护：从该轮开始的后缀全部保留，不跨工具链压缩。
        for (int turn = 0; turn < turns.size() - 1; turn++) {
            int start = turns.get(turn);
            if (start >= cutoff) {
                break;
            }
            int end = turns.get(turn + 1);
            if (!isCompletedTurn(context.subList(start, end))) {
                cutoff = start;
                break;
            }
        }
        // 摘要之外不能含有不属于任何用户轮的前导工作消息。
        for (int index = 0; index < turns.get(0); index++) {
            if (!isSummary(context.get(index))) {
                return -1;
            }
        }
        return cutoff;
    }

    private boolean isCompletedTurn(List<Msg> turn) {
        Msg last = turn.get(turn.size() - 1);
        return last.getRole() == MsgRole.ASSISTANT
                && last.getContentBlocks(ToolUseBlock.class).isEmpty()
                && hasSafeToolPairs(turn, true);
    }

    /** 同时核对结果顺序、重复 ID、RUNNING/挂起；保留段允许尚未完成的调用。 */
    private boolean hasSafeToolPairs(List<Msg> messages, boolean requireClosed) {
        Set<String> calls = new HashSet<>();
        Set<String> pending = new HashSet<>();
        Set<String> completed = new HashSet<>();
        for (Msg message : messages) {
            for (ToolUseBlock call : message.getContentBlocks(ToolUseBlock.class)) {
                if (call.getId() == null || !calls.add(call.getId())) {
                    return false;
                }
                pending.add(call.getId());
            }
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                if (!calls.contains(result.getId()) || completed.contains(result.getId())) {
                    return false;
                }
                if (result.getState() != ToolResultState.RUNNING && !result.isSuspended()) {
                    pending.remove(result.getId());
                    completed.add(result.getId());
                }
            }
        }
        return !requireClosed || pending.isEmpty();
    }

    static boolean isSummary(Msg message) {
        return SUMMARY_NAME.equals(message.getName());
    }

    private String unwrap(Msg message) {
        String text = message.getTextContent();
        if (text != null && text.startsWith(SUMMARY_HEADER)) {
            return text.substring(SUMMARY_HEADER.length());
        }
        return text;
    }
}
