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
}
