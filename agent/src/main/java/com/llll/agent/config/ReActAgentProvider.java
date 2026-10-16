package com.llll.agent.config;

import com.llll.agent.state.PgAgentStateStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReActAgentProvider {

    private final AgentProperties agentProperties;
    private final OpenAIChatModel openAIChatModel;
    private final PgAgentStateStore agentStateStore;

    public ReActAgent getAgent(){
        synchronized (this){
            return buildAgent("你现在是陪聊助手");
        }
    }


    public ReActAgent buildAgent(String systemPrompt){
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("Agentic-Rag")
                .sysPrompt(systemPrompt)
                .model(openAIChatModel)
                /** 原值：.toolkit(null)，框架 build() 会调用 toolkit.copy()。 */
                .toolkit(new Toolkit())
                .maxIters(agentProperties.getMaxIters())
                .maxRetries(agentProperties.getMaxRetries())
                .enablePendingToolRecovery(true)
                .stateStore(agentStateStore);
        return builder.build();
    }
}
