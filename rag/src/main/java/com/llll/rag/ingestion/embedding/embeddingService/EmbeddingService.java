package com.llll.rag.ingestion.embedding.embeddingService;

import java.util.List;

/**
 * 批量向量化接口，隔离具体模型服务的 HTTP 协议。
 */
public interface EmbeddingService {

    /**
     * 返回向量数量必须等于输入数量，且顺序与 texts 一致。
     * 实现负责按模型限制分批，并恢复服务端响应的输入顺序。
     * 持久化前必须校验向量维度和有限数值；当前 PG 列要求 1024 维。
     * 任一批失败时抛出异常，不以不完整的向量列表表示成功。
     */
    List<float[]> embedBatch(List<String> texts);
}
