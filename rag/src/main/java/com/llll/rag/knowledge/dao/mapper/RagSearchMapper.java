package com.llll.rag.knowledge.dao.mapper;

import com.llll.rag.search.model.RetrieveResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 位于现有 Mapper 扫描范围内，仅负责搜索 SQL。 */
@Mapper
public interface RagSearchMapper {

    /** 与现有 HNSW 索引一致，直接按余弦距离升序召回。 */
    /** 原向量召回只检查 Chunk 自身状态。
    @Select("""
            SELECT id, content
            FROM t_knowledge_chunk
            WHERE deleted = 0
              AND enabled = 1
              AND embedding IS NOT NULL
            ORDER BY embedding <=> #{queryVector,jdbcType=OTHER,typeHandler=com.llll.rag.knowledge.dao.handler.VectorTypeHandler}
            LIMIT #{topK}
            """)
    List<RetrieveResult> searchByVector(
            @Param("queryVector") float[] queryVector,
            @Param("topK") int topK);
    */

    /** 原向量召回未校验文档完成状态。
    @Select("""
            SELECT c.id, c.content
            FROM t_knowledge_chunk c
            WHERE c.deleted = 0
              AND c.enabled = 1
              AND c.embedding IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM t_knowledge_base k
                  WHERE k.id = c.kb_id AND k.deleted = 0
              )
            ORDER BY c.embedding <=> #{queryVector,jdbcType=OTHER,typeHandler=com.llll.rag.knowledge.dao.handler.VectorTypeHandler}
            LIMIT #{topK}
            """)
    List<RetrieveResult> searchByVector(
            @Param("queryVector") float[] queryVector,
            @Param("topK") int topK);
    */

    @Select("""
            SELECT c.id, c.content
            FROM t_knowledge_chunk c
            WHERE c.deleted = 0
              AND c.enabled = 1
              AND c.embedding IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM t_knowledge_document d
                  WHERE d.id = c.doc_id AND d.kb_id = c.kb_id
                    AND d.deleted = 0 AND d.enabled = 1 AND d.status = 'success'
              )
              AND EXISTS (
                  SELECT 1 FROM t_knowledge_base k
                  WHERE k.id = c.kb_id AND k.deleted = 0
              )
            ORDER BY c.embedding <=> #{queryVector,jdbcType=OTHER,typeHandler=com.llll.rag.knowledge.dao.handler.VectorTypeHandler}
            LIMIT #{topK}
            """)
    List<RetrieveResult> searchByVector(
            @Param("queryVector") float[] queryVector,
            @Param("topK") int topK);

    /** ES 可以暂时落后于 PG；候选必须按 ID 回查，并使用当前 PG 正文。 */
    /** 原候选回查未校验文档完成状态。
    @Select("""
            <script>
            SELECT c.id, c.content
            FROM t_knowledge_chunk c
            JOIN t_knowledge_base k ON k.id = c.kb_id
            WHERE c.deleted = 0 AND c.enabled = 1 AND k.deleted = 0
              AND c.id IN
              <foreach collection="chunkIds" item="chunkId" open="(" separator="," close=")">
                  #{chunkId}
              </foreach>
            </script>
            """)
    List<RetrieveResult> selectActiveCandidates(@Param("chunkIds") List<String> chunkIds);
    */

    @Select("""
            <script>
            SELECT c.id, c.content
            FROM t_knowledge_chunk c
            JOIN t_knowledge_base k ON k.id = c.kb_id
            JOIN t_knowledge_document d ON d.id = c.doc_id AND d.kb_id = c.kb_id
            WHERE c.deleted = 0 AND c.enabled = 1 AND k.deleted = 0
              AND d.deleted = 0 AND d.enabled = 1 AND d.status = 'success'
              AND c.id IN
              <foreach collection="chunkIds" item="chunkId" open="(" separator="," close=")">
                  #{chunkId}
              </foreach>
            </script>
            """)
    List<RetrieveResult> selectActiveCandidates(@Param("chunkIds") List<String> chunkIds);

}
