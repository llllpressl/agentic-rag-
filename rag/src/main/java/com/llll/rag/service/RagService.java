package com.llll.rag.service;

import com.llll.rag.search.model.RetrieveResult;
import com.llll.rag.search.request.RagSearchRequest;

import java.util.List;

/** 搜索流程复用的 RRF 与重排操作。 */
public interface RagService {

    /** 统一检索入口：向量化、双路召回、RRF 融合、重排和最终截取。 */
    List<RetrieveResult> search(RagSearchRequest request);

    List<RetrieveResult> rrf(
            List<RetrieveResult> vectorResults,
            List<RetrieveResult> keywordResults,
            int topK);

    List<RetrieveResult> rerank(String query, List<RetrieveResult> candidates);
}
