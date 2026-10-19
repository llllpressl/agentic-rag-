package com.llll.agent.config;

import com.llll.agent.tool.KnowledgeSearchTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 本地工具的统一注册入口；后续 MCP 客户端也在这里管理。 */
@Configuration
public class AgentToolkitConfiguration {

    @Bean(destroyMethod = "closeMcpClients")
    public Toolkit agentToolkit(KnowledgeSearchTool knowledgeSearchTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(knowledgeSearchTool);
        return toolkit;
    }
}
