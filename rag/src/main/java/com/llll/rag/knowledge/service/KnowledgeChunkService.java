package com.llll.rag.knowledge.service;

import com.llll.rag.ingestion.model.DocumentChunk;
import com.llll.rag.knowledge.mq.event.DocChunkEsEvent;

import java.util.List;

public interface KnowledgeChunkService {

    /** 保存完整分块集合，并在同一个 PG 事务中更新文档数量和成功状态。 */
    void saveChunks(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator);

    // 创建es落库的方法，并在KnowledgeChunkServiceImpl 中实现
    /** 回查 PG 中已经提交的 Chunk，使用 Chunk 主键批量覆盖写入 ES。 */
    void saveChunksInEs(DocChunkEsEvent event);
}
