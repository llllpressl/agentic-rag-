package com.llll.rag.knowledge.service;

import com.llll.rag.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeBasePageRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeBasePageVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeBaseVO;

public interface KnowledgeBaseService {

    String create (KnowledgeBaseCreateRequest requestParam);

    void update(String kbId, KnowledgeBaseUpdateRequest requestParam);

    void delete(String kbId);

    KnowledgeBaseVO detail(String kbId);

    KnowledgeBasePageVO page(KnowledgeBasePageRequest request);

}
