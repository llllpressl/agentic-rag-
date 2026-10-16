package com.llll.rag.knowledge.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchProperties;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证客户端自动配置与 Boot 4 的兼容性；不发送消息或创建 ES 索引。 */
class MiddlewareConfigurationTest {

    static ApplicationContextRunner clientContext() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load(
                                "rag-yaml", new ClassPathResource("application.yaml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException("读取 rag 配置失败", exception);
                    }
                })
                .withConfiguration(AutoConfigurations.of(
                        RocketMQAutoConfiguration.class,
                        ElasticsearchRestClientAutoConfiguration.class,
                        ElasticsearchClientAutoConfiguration.class));
    }

    @Test
    void configuredClientsLoadWithTheProjectYamlOnSpringBoot4() {
        clientContext().run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(DefaultMQProducer.class)
                    .hasSingleBean(RocketMQTemplate.class)
                    .hasSingleBean(ElasticsearchClient.class);

            var producer = context.getBean(DefaultMQProducer.class);
            assertThat(producer.getNamesrvAddr()).isEqualTo("127.0.0.1:19876");
            assertThat(producer.getProducerGroup()).isEqualTo("agentic-producer");
            assertThat(producer.getSendMsgTimeout()).isEqualTo(2000);
            assertThat(context.getBean(RocketMQTemplate.class).getProducer()).isSameAs(producer);

            var properties = context.getBean(ElasticsearchProperties.class);
            assertThat(properties.getUris()).containsExactly(
                    context.getEnvironment().getProperty("spring.elasticsearch.uris"));
            assertThat(properties.getUsername()).isEqualTo(
                    context.getEnvironment().getProperty("spring.elasticsearch.username"));
        });
    }
}
