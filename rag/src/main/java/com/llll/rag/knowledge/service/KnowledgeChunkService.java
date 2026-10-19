package com.llll.rag.knowledge.service;

import com.llll.rag.ingestion.model.DocumentChunk;
import com.llll.rag.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeChunkPageVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeChunkVO;
/** 原依赖：import com.llll.rag.knowledge.mq.event.DocChunkEsEvent; */

import java.util.List;

public interface KnowledgeChunkService {

    /** 在同一个 PG 事务中替换全部分块、更新数量，并推进至 elasticing。 */
    void saveChunks(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator);

    /** 删除文档旧 ES 分块，再从 PG 全量写入；全部成功后更新文档为 success。 */
    void saveChunksInEs(String docId, String operator);

    /** 物理删除指定文档在 PG 和 ES 中的所有分块。 */
    void delete(String docId);

    /** 查询指定文档下未删除的分块，不返回 embedding。 */
    KnowledgeChunkVO detail(String docId, String chunkId);

    /** 按分块序号分页查询文档下未删除的分块。 */
    KnowledgeChunkPageVO page(String docId, KnowledgeChunkPageRequest request);
}
