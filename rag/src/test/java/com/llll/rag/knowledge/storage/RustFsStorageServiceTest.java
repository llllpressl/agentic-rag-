package com.llll.rag.knowledge.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RustFsStorageServiceTest {

    private final S3Client client = mock(S3Client.class);
    private final RustFsStorageService storage = new RustFsStorageService(client, new RustFsProperties());
    private final MockMultipartFile file = new MockMultipartFile(
            "file", "notes.txt", "text/plain", new byte[]{1, 2, 3});

    @Test
    void forbiddenBucketDoesNotTriggerCreationOrUpload() {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("Forbidden").build());

        assertThrows(S3Exception.class, () -> storage.upload("java-guide", file));

        verify(client, never()).createBucket(any(CreateBucketRequest.class));
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void anotherInstanceCreatingBucketIsCheckedBeforeUpload() throws Exception {
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build())
                .thenReturn(null);
        when(client.createBucket(any(CreateBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(409).build());

        storage.upload("java-guide", file);

        var order = inOrder(client);
        order.verify(client).headBucket(any(HeadBucketRequest.class));
        order.verify(client).createBucket(any(CreateBucketRequest.class));
        order.verify(client).headBucket(any(HeadBucketRequest.class));
        order.verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void invalidUploadDoesNotAccessRustFs() {
        assertThrows(IllegalArgumentException.class, () -> storage.upload("../java-guide", file));
        assertThrows(IllegalArgumentException.class, () -> storage.upload("", file));
        assertThrows(IllegalArgumentException.class, () -> storage.upload("a".repeat(65), file));
        assertThrows(IllegalArgumentException.class, () -> storage.upload("java-guide",
                new MockMultipartFile("file", new byte[0])));
        verifyNoInteractions(client);
    }
}
