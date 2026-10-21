package com.llll.eval;

import com.llll.rag.ingestion.embedding.EmbeddingProperties;
import com.llll.rag.ingestion.embedding.EmbeddingServiceImpl;
import com.llll.rag.knowledge.config.ElasticsearchConfiguration;
import com.llll.rag.search.config.RagRecallExecutorConfiguration;
import com.llll.rag.search.properties.RagSearchProperties;
import com.llll.rag.search.properties.RerankProperties;
import com.llll.rag.search.retriever.impl.EsBm25Retriever;
import com.llll.rag.search.retriever.impl.PgVectorRetriever;
import com.llll.rag.service.serviceImpl.RagServiceImpl;
import com.llll.rag.search.routing.JevClient;
import com.llll.rag.search.routing.JevProperties;
import com.llll.rag.search.routing.KnowledgeBaseRouter;
import com.llll.rag.search.routing.RagRoutingProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Map;

/** 独立只读命令行入口，不扫描 Agent、Controller、MQ 消费者。 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration")
@MapperScan("com.llll.rag.knowledge.dao.mapper")
@Import({EmbeddingProperties.class, EmbeddingServiceImpl.class,
        RagSearchProperties.class, RerankProperties.class, ElasticsearchConfiguration.class,
        RagRecallExecutorConfiguration.class, PgVectorRetriever.class, EsBm25Retriever.class,
        /** 原装配末尾：
        RagServiceImpl.class, EvaluationSnapshot.class, RetrievalRunner.class, SourceDocumentExporter.class})
        */
        RagServiceImpl.class, EvaluationSnapshot.class, RetrievalRunner.class, SourceDocumentExporter.class,
        JevProperties.class, RagRoutingProperties.class, JevClient.class, KnowledgeBaseRouter.class})
public class EvaluationApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(EvaluationApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(
                "spring.config.name", "agenticrag",
                "spring.config.additional-location", "optional:file:bootstrap/src/main/resources/",
                "spring.main.banner-mode", "off",
                "logging.level.root", "OFF",
                "spring.main.log-startup-info", "false",
                "spring.datasource.hikari.read-only", "true"));
        try (ConfigurableApplicationContext context = application.run(args)) {
            String action = context.getEnvironment().getRequiredProperty("eval.action");
            if ("export".equals(action)) {
                context.getBean(EvaluationSnapshot.class).export();
            } else if ("retrieve".equals(action)) {
                context.getBean(RetrievalRunner.class).run();
            } else if ("sources".equals(action)) {
                context.getBean(SourceDocumentExporter.class).export();
            } else {
                throw new IllegalArgumentException("未知评测命令");
            }
        } catch (Exception exception) {
            // 异常消息可能包含 JDBC URL、认证信息或模型响应，仅打印类型。
            System.err.println("EVALUATION_FAILED " + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
