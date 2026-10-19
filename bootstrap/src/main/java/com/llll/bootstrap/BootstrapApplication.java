package com.llll.bootstrap;

import com.llll.framwork.handler.TimeFillHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import org.springframework.context.annotation.Import;

import java.util.Map;

/** 统一加载 Agent 和 RAG 业务组件。 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.llll.agent", "com.llll.rag"})
@MapperScan(basePackages = {
        "com.llll.agent.dao.mapper",
        "com.llll.agent.user.dao.mapper",
        "com.llll.rag.knowledge.dao.mapper"
})
@Import(TimeFillHandler.class)
public class BootstrapApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BootstrapApplication.class);
        // 使用独立配置名，避免依赖 Jar 中两份 application.yaml 参与默认配置加载。
        application.setDefaultProperties(Map.of("spring.config.name", "agenticrag"));
        application.run(args);
    }
}
