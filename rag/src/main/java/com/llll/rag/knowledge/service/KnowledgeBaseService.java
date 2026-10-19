package com.llll.rag.knowledge.service;

import com.llll.rag.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeBaseUpdateRequest;

public interface KnowledgeBaseService {

    String create (KnowledgeBaseCreateRequest requestParam);

    void update(String kbId, KnowledgeBaseUpdateRequest requestParam);

    void delete(String kbId);

}
