package com.llll.rag.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 只在明确启用时连接 RustFS；删除的对象仅限本测试创建的随机对象。 */
@EnabledIfEnvironmentVariable(named = "RUSTFS_INTEGRATION_TEST", matches = "true")
class RustFsStorageIntegrationTest {

    @Test
    void documentsUseCollectionPrefixesAndDoNotOverwriteEachOther() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (var source : new YamlPropertySourceLoader().load("rag-yaml",
                    new ClassPathResource("application.yaml"))) {
                context.getEnvironment().getPropertySources().addLast(source);
            }
            context.register(S3Configuration.class, RustFsStorageService.class);
            context.refresh();

            var storage = context.getBean(RustFsStorageService.class);
            var client = context.getBean(S3Client.class);
            var properties = context.getBean(RustFsProperties.class);
            assertEquals("rag-text-file", properties.getBucket());
            byte[] content = "RustFS 知识库文档存储测试".getBytes(StandardCharsets.UTF_8);
            String collection = "storage-test-" + UUID.randomUUID();
            String otherCollection = collection + "-other";
            var keys = new ArrayList<String>();
            try {
                var file = new MockMultipartFile("file", "知识库说明.MD", "text/markdown", content);
                var first = storage.upload(collection, file);
                keys.add(first.objectKey());
                var second = storage.upload(collection, file);
                keys.add(second.objectKey());
                var third = storage.upload(otherCollection, file);
                keys.add(third.objectKey());
                var text = storage.upload(collection, new MockMultipartFile(
                        "file", "知识库说明.TXT", "text/plain", content));
                keys.add(text.objectKey());
                assertNotEquals(first.objectKey(), second.objectKey());
                assertTrue(first.objectKey().startsWith(collection + "/"));
                assertTrue(second.objectKey().startsWith(collection + "/"));
                assertTrue(third.objectKey().startsWith(otherCollection + "/"));
                assertFalse(third.objectKey().startsWith(collection + "/"));
                assertEquals("知识库说明.MD", first.originalFilename());
                assertEquals("markdown", first.fileType());
                assertTrue(first.objectKey().endsWith(".md"));
                assertEquals("text", text.fileType());
                assertTrue(text.objectKey().endsWith(".txt"));
                assertEquals("text/plain", text.contentType());
                assertEquals(content.length, text.size());
                assertEquals("text/markdown", first.contentType());
                assertEquals(content.length, first.size());
                try (var stream = storage.download(first.objectKey())) {
                    assertEquals("text/markdown", stream.response().contentType());
                    assertArrayEquals(content, stream.readAllBytes());
                }
                try (var stream = storage.download(second.objectKey())) {
                    assertArrayEquals(content, stream.readAllBytes());
                }

                storage.delete(first.objectKey());
                var missing = assertThrows(S3Exception.class, () -> client.headObject(
                        HeadObjectRequest.builder().bucket(properties.getBucket()).key(first.objectKey()).build()));
                assertEquals(404, missing.statusCode());
                // 删除一个对象后，同 collection 和其他 collection 的文件仍然可读。
                for (String key : new String[]{second.objectKey(), third.objectKey(), text.objectKey()}) {
                    try (var stream = storage.download(key)) {
                        assertArrayEquals(content, stream.readAllBytes());
                    }
                }
            } finally {
                for (String key : keys) {
                    storage.delete(key);
                }
            }
        }
    }
}
