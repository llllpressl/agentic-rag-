package com.llll.rag.knowledge.storage;

/**
 * @param objectKey 存储定位符，如 collection/uuid.md，不是公开 URL 或临时下载链接
 * @param originalFilename 原始文件名，如 notes.md
 * @param fileType 文件格式名称：md/markdown 为 markdown，txt 为 text，其他为小写扩展名；无有效扩展名时为 unknown
 * @param contentType MIME 类型；未提供时为 application/octet-stream
 * @param size 文件大小，单位字节
 */
public record StorageUploadResult(
        String objectKey,
        String originalFilename,
        String fileType,
        String contentType,
        long size
) {}
