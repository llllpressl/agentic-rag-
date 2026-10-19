package com.llll.rag.knowledge.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** 云端 ES 8 使用对应版本客户端，通过 SSH 隧道访问服务器本机端口。 */
@Configuration(proxyBeanMethods = false)
public class ElasticsearchConfiguration {

    @Value("${spring.elasticsearch.uris}")
    private String uris;

    @Value("${spring.elasticsearch.username:}")
    private String username;

    @Value("${spring.elasticsearch.password:}")
    private String password;

    @Value("${spring.elasticsearch.connection-timeout:10s}")
    private String connectionTimeout;

    @Value("${spring.elasticsearch.socket-timeout:30s}")
    private String socketTimeout;

    /** Spring 关闭传输层时，一并关闭底层 HTTP 连接。 */
    @Bean(destroyMethod = "close")
    public RestClientTransport elasticsearchTransport() {
        String[] addresses = uris.split(",");
        HttpHost[] hosts = new HttpHost[addresses.length];
        for (int index = 0; index < addresses.length; index++) {
            hosts[index] = HttpHost.create(addresses[index].trim());
        }

        int connectionTimeoutMillis = Math.toIntExact(DurationStyle.detectAndParse(connectionTimeout).toMillis());
        int socketTimeoutMillis = Math.toIntExact(DurationStyle.detectAndParse(socketTimeout).toMillis());
        RestClientBuilder builder = RestClient.builder(hosts);
        builder.setRequestConfigCallback(request -> request
                .setConnectTimeout(connectionTimeoutMillis)
                .setConnectionRequestTimeout(connectionTimeoutMillis)
                .setSocketTimeout(socketTimeoutMillis));

        // 当前服务器无需认证；仅在明确配置用户名后发送凭据。
        if (StringUtils.hasText(username)) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(client -> client
                    .setDefaultCredentialsProvider(credentialsProvider));
        }

        RestClient restClient = builder.build();
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    /** 连接生命周期由传输层统一管理，避免重复关闭。 */
    @Bean(destroyMethod = "")
    public ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
