package com.llll.agent.memory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/** 当前会话的字符预算；字符数不是 Token 数，固定提示词和工具定义另有开销。 */
@Data
@Configuration
@ConfigurationProperties("agent.memory")
@Validated
public class AgentMemoryProperties {

    @Min(1)
    private int contextWindowChars = 100_000;

    private boolean summaryEnabled = true;

    @NotNull
    private List<String> evictableTools = new ArrayList<>(List.of("search_knowledge"));

    @Min(1)
    private int keepRecentChars = 20_000;

    /** 提示模型尽量生成 3000 字符左右的摘要，超过此上限则整次放弃。 */
    @Min(1)
    private int summaryMaxChars = 5_000;

    /** 输出 Token 护栏与摘要字符上限分别配置，二者不能互相换算。 */
    @Min(1)
    private int summaryMaxTokens = 8_192;

    @Min(1000)
    private long summaryTimeoutMs = 60_000L;

    public long resolveTrimTriggerChars() {
        return (long) Math.ceil(contextWindowChars * 0.5D);
    }

    public long resolveCompactTriggerChars() {
        return (long) Math.ceil(contextWindowChars * 0.8D);
    }
}
