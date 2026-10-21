package com.llll.agent.memory;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;

import java.util.List;

/** 统一使用 String.length() 计量，以 long 累加，避免大消息导致整数溢出。 */
public final class AgentContextChars {

    private AgentContextChars() {
    }

    public static long total(List<Msg> messages) {
        long chars = 0;
        for (Msg message : messages) {
            chars += of(message);
        }
        return chars;
    }

    public static long of(Msg message) {
        if (message == null || message.getRole() == MsgRole.SYSTEM) {
            return 0;
        }
        return blocks(message.getContent());
    }

    public static long blocks(List<ContentBlock> content) {
        if (content == null) {
            return 0;
        }
        long chars = 0;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock text) {
                chars += length(text.getText());
            } else if (block instanceof ThinkingBlock thinking) {
                chars += length(thinking.getThinking());
            } else if (block instanceof ToolUseBlock toolUse) {
                chars += length(toolUse.getName());
                if (toolUse.getInput() != null) {
                    chars += JsonUtils.getJsonCodec().toJson(toolUse.getInput()).length();
                }
            } else if (block instanceof ToolResultBlock result) {
                chars += blocks(result.getOutput());
            }
        }
        return chars;
    }

    static int lastUserIndex(List<Msg> context) {
        for (int index = context.size() - 1; index >= 0; index--) {
            Msg message = context.get(index);
            if (message.getRole() == MsgRole.USER && !AgentContextCompactor.isSummary(message)) {
                return index;
            }
        }
        return -1;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
