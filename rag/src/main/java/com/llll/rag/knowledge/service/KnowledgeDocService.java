package com.llll.rag.knowledge.service;

import com.llll.rag.knowledge.controller.request.KnowledgeDocPageRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeDocUpdateRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocPageVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocVO;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocService {

    void startChunk(String docId);

    /** 对 elasticing 文档同步执行 ES 补写，复用消费者使用的 ES 方法。 */
    void syncChunksToEs(String docId);

    KnowledgeDocVO upload(String kbId, MultipartFile file);

    void update(String docId, KnowledgeDocUpdateRequest request);

    void delete(String docId);

    KnowledgeDocVO detail(String docId);

    KnowledgeDocPageVO page(KnowledgeDocPageRequest request);

}
