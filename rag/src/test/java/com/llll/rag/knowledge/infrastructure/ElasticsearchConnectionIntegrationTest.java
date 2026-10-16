package com.llll.rag.knowledge.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用后，只读检查真实 ES 的认证与 Java Client 响应解析。 */
@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_INTEGRATION_TEST", matches = "true")
class ElasticsearchConnectionIntegrationTest {

    @Test
    void configuredJavaClientReadsTheRunningElasticsearchServer() {
        MiddlewareConfigurationTest.clientContext()
                .withPropertyValues(
                        "spring.elasticsearch.connection-timeout=5s",
                        "spring.elasticsearch.socket-timeout=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.elasticsearch.password"))
                            .as("联调前须设置 ELASTICSEARCH_PASSWORD")
                            .isNotBlank();

                    var info = context.getBean(ElasticsearchClient.class).info();
                    assertThat(info.version().number()).startsWith("9.");
                    assertThat(info.clusterName()).isNotBlank();
                    System.out.printf("ES read-only connection verified: version=%s, cluster=%s%n",
                            info.version().number(), info.clusterName());
                });
    }
}
