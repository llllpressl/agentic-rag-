package com.llll.eval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llll.rag.ingestion.embedding.EmbeddingProperties;
import com.llll.rag.knowledge.dao.entity.KnowledgeChunkEsDO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class EvaluationSnapshot {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProperties embeddingProperties;
    private final ElasticsearchClient elasticsearchClient;
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 与 RagSearchMapper 相同的文档、Chunk、知识库可用性条件。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> chunks() {
        return jdbcTemplate.queryForList("""
                SELECT c.id AS chunk_id, c.doc_id, d.doc_name, c.kb_id, c.chunk_index,
                       c.content, k.embedding_model,
                       c.embedding IS NOT NULL AS embedding_present,
                       md5(coalesce(c.embedding::text, '')) AS vector_fingerprint,
                       c.update_time::text AS chunk_updated_at,
                       d.update_time::text AS doc_updated_at,
                       k.update_time::text AS kb_updated_at
                FROM t_knowledge_chunk c
                JOIN t_knowledge_document d ON d.id = c.doc_id AND d.kb_id = c.kb_id
                JOIN t_knowledge_base k ON k.id = c.kb_id
                WHERE c.deleted = 0 AND c.enabled = 1
                  AND d.deleted = 0 AND d.enabled = 1 AND d.status = 'success'
                  AND k.deleted = 0
                ORDER BY c.id
                """);
    }

    public String id(List<Map<String, Object>> chunks) throws Exception {
        return hash(mapper.writeValueAsString(chunks));
    }

    public String esId() throws Exception {
        String index = environment.getRequiredProperty("knowledge.elasticsearch.chunk-index");
        SearchResponse<KnowledgeChunkEsDO> response = elasticsearchClient.search(request -> request
                .index(index)
                .size(10000)
                .trackTotalHits(total -> total.enabled(true))
                .seqNoPrimaryTerm(true)
                .allowPartialSearchResults(false)
                .query(query -> query.matchAll(all -> all)), KnowledgeChunkEsDO.class);
        if (response.timedOut() || response.shards().failed().intValue() > 0
                || response.hits().total() == null
                || response.hits().total().value() != response.hits().hits().size()) {
            throw new IllegalStateException("ES 快照不完整；当前只支持不超过 10000 个索引条目");
        }
        List<String> entries = new ArrayList<>();
        for (Hit<KnowledgeChunkEsDO> hit : response.hits().hits()) {
            entries.add(hit.id() + ":" + hit.seqNo() + ":" + hit.primaryTerm()
                    + ":" + mapper.writeValueAsString(hit.source()));
        }
        Collections.sort(entries);
        return hash(mapper.writeValueAsString(entries));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void export() throws Exception {
        Path directory = Path.of(environment.getProperty("eval.output", "rag-eval/datasets"));
        Files.createDirectories(directory);
        List<Map<String, Object>> chunks = chunks();
        String snapshotId = id(chunks);
        Set<Object> documents = new HashSet<>();
        Set<Object> models = new HashSet<>();
        int missingEmbeddings = 0;
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            documents.add(chunk.get("doc_id"));
            models.add(chunk.get("embedding_model"));
            if (!Boolean.TRUE.equals(chunk.get("embedding_present"))) {
                missingEmbeddings++;
            }
            lines.add(mapper.writeValueAsString(chunk));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshot_id", snapshotId);
        snapshot.put("created_at", Instant.now().toString());
        snapshot.put("document_count", documents.size());
        snapshot.put("chunk_count", chunks.size());
        snapshot.put("missing_embeddings", missingEmbeddings);
        snapshot.put("stored_embedding_models", models);
        snapshot.put("query_embedding_model", embeddingProperties.getModel());
        snapshot.put("embedding_dimensions", embeddingProperties.getDimensions());
        snapshot.put("expected_document_count", 84);
        snapshot.put("document_state_counts", jdbcTemplate.queryForList("""
                SELECT status, enabled, deleted, count(*) AS document_count
                FROM t_knowledge_document
                GROUP BY status, enabled, deleted
                ORDER BY status, enabled, deleted
                """));
        snapshot.put("count_note", "文档数按存在有效 Chunk 的文档去重；实际数量以本快照为准。未导出向量。");
        Files.write(directory.resolve("chunks.jsonl"), lines, StandardCharsets.UTF_8);
        snapshot.put("chunks_sha256", hash(Files.readString(directory.resolve("chunks.jsonl"), StandardCharsets.UTF_8)));
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("snapshot.json").toFile(), snapshot);
        System.out.println("EXPORT_COMPLETED documents=" + documents.size() + ", chunks=" + chunks.size());
    }

    public static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
