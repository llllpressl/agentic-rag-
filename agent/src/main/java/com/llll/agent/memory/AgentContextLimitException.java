package com.llll.agent.memory;

/** 通过现有 SSE reason 字段报告超限，不增加事件类型或协议字段。 */
public class AgentContextLimitException extends IllegalStateException {

    public static final String REASON = "context_limit_exceeded";

    public AgentContextLimitException(long actualChars, int limitChars) {
        super("Agent 上下文超过字符预算：" + actualChars + " > " + limitChars
                + "，请缩短本轮输入或新建会话");
    }

    public static boolean isContextLimit(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            if (current instanceof AgentContextLimitException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
