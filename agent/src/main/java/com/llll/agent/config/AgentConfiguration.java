package com.llll.agent.config;

import com.llll.agent.dao.mapper.AgentStateMapper;
import com.llll.agent.state.PgAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class AgentConfiguration {

    private final AgentProperties agentProperties;
    private final AiProvider aiProvider;

    @Bean
    public OpenAIChatModel agentChatModel(){
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .baseUrl(aiProvider.getDeepseek().getUrl())
                .endpointPath(aiProvider.getDeepseek().getEndpoint())
                .apiKey(aiProvider.getDeepseek().getApiKey())
                .modelName(agentProperties.getChat().getModel())
                .stream(true)
                .nativeStructuredOutput(false)
                .formatter(new DeepSeekFormatter());
        return builder.build();
    }

    @Bean
    public PgAgentStateStore agentStateStore(AgentStateMapper agentStateMapper){
        return new PgAgentStateStore(agentStateMapper);
    }
}
