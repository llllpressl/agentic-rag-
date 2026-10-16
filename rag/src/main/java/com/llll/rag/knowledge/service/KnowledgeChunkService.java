package com.llll.rag.knowledge.service;

import com.llll.rag.ingestion.model.DocumentChunk;

import java.util.List;

public interface KnowledgeChunkService {

    /** 保存完整分块集合，并在同一个 PG 事务中更新文档数量和成功状态。 */
    void saveChunks(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator);
}
