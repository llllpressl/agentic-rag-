package com.llll.rag.knowledge.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** RustFS 的 S3 API 连接配置；9000 是 API 端口，9001 是控制台端口。 */
/// TODO ai写的
@Data
@ConfigurationProperties(prefix = "storage.rustfs")
public class RustFsProperties {

    private String endpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private String bucket;
}
