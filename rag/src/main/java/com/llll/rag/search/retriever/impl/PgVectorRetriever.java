package com.llll.rag.search.retriever.impl;

import com.llll.rag.ingestion.embedding.EmbeddingProperties;
import com.llll.rag.knowledge.dao.mapper.RagSearchMapper;
import com.llll.rag.search.model.RetrieveResult;
import com.llll.rag.search.retriever.VectorRetriever;
import com.llll.rag.search.routing.model.RetrievalScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PgVectorRetriever implements VectorRetriever {

    private final RagSearchMapper ragSearchMapper;
    private final EmbeddingProperties embeddingProperties;

    @Override
    public List<RetrieveResult> retrieve(float[] queryVector, int topK) {
        return retrieve(queryVector, topK, RetrievalScope.builder()
                .unrestricted(true)
                .kbIds(List.of())
                .build());
    }

    @Override
    public List<RetrieveResult> retrieve(float[] queryVector, int topK, RetrievalScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("向量检索范围不能为 null");
        }
        scope.validate();
        if (!scope.isUnrestricted() && scope.getKbIds().isEmpty()) {
            return List.of();
        }
        if (queryVector == null || queryVector.length != embeddingProperties.getDimensions()) {
            throw new IllegalArgumentException("检索向量维度与 Embedding 配置不一致");
        }
        if (topK < 1 || topK > 500) {
            throw new IllegalArgumentException("向量召回数量必须在 1 到 500 之间");
        }
        boolean hasNonZeroValue = false;
        for (float value : queryVector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("检索向量包含非有限数值");
            }
            if (value != 0.0f) {
                hasNonZeroValue = true;
            }
        }
        if (!hasNonZeroValue) {
            throw new IllegalArgumentException("检索向量不能为零向量");
        }

        /** 原调用不接收知识库范围。
        List<RetrieveResult> results = ragSearchMapper.searchByVector(queryVector, topK);
        */
        List<RetrieveResult> results = ragSearchMapper.searchByVector(
                queryVector, topK, scope.isUnrestricted(), scope.getKbIds());
        for (int index = 0; index < results.size(); index++) {
            results.get(index).setVectorRank(index + 1);
        }
        return results;
    }
}
