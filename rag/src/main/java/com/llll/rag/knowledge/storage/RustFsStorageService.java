package com.llll.rag.knowledge.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * 所有知识库原始文档共用一个桶，通过 collection 前缀分组，无需预先创建文件夹。
 * 知识库存在性和访问权限由调用方的业务服务校验。
 */
/// TODO ai写的
@Service
@RequiredArgsConstructor
public class RustFsStorageService {

    private final S3Client s3Client;
    private final RustFsProperties properties;
    private volatile boolean bucketReady;

    /** 上传成功后返回对象 key；保存文档记录失败时，调用方需补偿删除该对象。 */
    public StorageUploadResult upload(String collection, MultipartFile file) throws IOException {
        if (collection == null || !collection.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("collection 长度须为 1 到 64，且只能包含字母、数字、下划线和短横线");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String filename = file.getOriginalFilename();
        filename = StringUtils.hasText(filename)
                ? StringUtils.getFilename(filename.replace('\\', '/')) : "document";
        if (!StringUtils.hasText(filename)) {
            filename = "document";
        }
        String extension = StringUtils.getFilenameExtension(filename);
        boolean hasExtension = extension != null && extension.matches("[A-Za-z0-9]{1,16}");
        String normalizedExtension = hasExtension ? extension.toLowerCase(Locale.ROOT) : "";
        String fileType = switch (normalizedExtension) {
            case "md", "markdown" -> "markdown";
            case "txt" -> "text";
            default -> normalizedExtension.isEmpty() ? "unknown" : normalizedExtension;
        };
        // 存储文件名保留原扩展名，与业务记录的格式名称分别生成。
        String suffix = hasExtension ? "." + normalizedExtension : "";
        String objectKey = collection + "/" + UUID.randomUUID() + suffix;
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType() : "application/octet-stream";

        ensureBucket();
        // 已知长度的流上传，避免把整个 PDF/Word 文件复制到内存中。
        try (InputStream stream = file.getInputStream()) {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(properties.getBucket()).key(objectKey)
                            .contentType(contentType).build(),
                    RequestBody.fromInputStream(stream, file.getSize()));
        }
        return new StorageUploadResult(objectKey, filename, fileType, contentType, file.getSize());
    }

    /** 读取原文供解析器使用；调用方必须用 try-with-resources 关闭返回的流。 */
    public ResponseInputStream<GetObjectResponse> download(String objectKey) {
        requireObjectKey(objectKey);
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(properties.getBucket()).key(objectKey).build());
    }

    /** 物理删除原文；只删除指定对象，不删除整个桶。 */
    public void delete(String objectKey) {
        requireObjectKey(objectKey);
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket()).key(objectKey).build());
    }

    private void requireObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new IllegalArgumentException("对象 key 不能为空");
        }
    }

    // 首次上传才访问 RustFS，避免仅创建 Spring Bean 时依赖外部服务可用。
    private synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        HeadBucketRequest head = HeadBucketRequest.builder().bucket(properties.getBucket()).build();
        try {
            s3Client.headBucket(head);
        } catch (S3Exception exception) {
            // 认证失败、权限不足和服务端故障不能当成“桶不存在”。
            if (exception.statusCode() != 404) {
                throw exception;
            }
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
            } catch (S3Exception createException) {
                if (createException.statusCode() != 409) {
                    throw createException;
                }
                // 多实例可能同时建桶；确认本实例有访问权限后才继续。
                s3Client.headBucket(head);
            }
        }
        bucketReady = true;
    }
}
