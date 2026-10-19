package com.llll.rag.knowledge.service;

import com.llll.rag.knowledge.controller.request.KnowledgeDocUpdateRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocVO;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocService {

    void startChunk(String docId);

    KnowledgeDocVO upload(String kbId, MultipartFile file);

    void update(String docId, KnowledgeDocUpdateRequest request);

    void delete(String docId);

}
