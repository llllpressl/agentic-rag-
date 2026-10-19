package com.llll.rag.search.retriever.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.llll.rag.knowledge.dao.entity.KnowledgeChunkEsDO;
import com.llll.rag.search.model.RetrieveResult;
import com.llll.rag.search.retriever.KeywordRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EsBm25Retriever implements KeywordRetriever {

    private final ElasticsearchClient elasticsearchClient;

    @Value("${knowledge.elasticsearch.chunk-index}")
    private String chunkIndex;

    @Override
    public List<RetrieveResult> retrieve(String query, int topK) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("BM25 检索问题不能为空");
        }
        if (topK < 1 || topK > 500) {
            throw new IllegalArgumentException("BM25 召回数量必须在 1 到 500 之间");
        }
        SearchResponse<KnowledgeChunkEsDO> response;
        try {
            response = elasticsearchClient.search(request -> request
                            .index(chunkIndex)
                            .size(topK)
                            .allowPartialSearchResults(false)
                            .query(searchQuery -> searchQuery.bool(bool -> bool
                                    .must(must -> must.match(match -> match
                                            .field("content")
                                            .query(query)))
                                    .filter(filter -> filter.term(term -> term
                                            .field("deleted")
                                            .value(0)))
                                    .filter(filter -> filter.term(term -> term
                                            .field("enabled")
                                            .value(1)))))
                            .sort(sort -> sort.score(score -> score
                                    .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))),
                    KnowledgeChunkEsDO.class);
        } catch (IOException exception) {
            throw new IllegalStateException("ES BM25 召回失败", exception);
        }
        if (response.timedOut() || response.shards().failed().doubleValue() > 0) {
            throw new IllegalStateException("ES BM25 查询超时或存在失败分片");
        }

        List<RetrieveResult> results = new ArrayList<>();
        List<Hit<KnowledgeChunkEsDO>> hits = response.hits().hits();
        for (int index = 0; index < hits.size(); index++) {
            Hit<KnowledgeChunkEsDO> hit = hits.get(index);
            KnowledgeChunkEsDO source = hit.source();
            if (!StringUtils.hasText(hit.id()) || source == null || !StringUtils.hasText(source.getContent())) {
                throw new IllegalStateException("ES BM25 命中结果缺少 Chunk ID 或正文");
            }
            RetrieveResult result = RetrieveResult.builder()
                    .id(hit.id())
                    .content(source.getContent())
                    .bm25Rank(index + 1)
                    .build();
            results.add(result);
        }
        return results;
    }
}
