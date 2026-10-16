package com.llll.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llll.rag.knowledge.dao.entity.KnowledgeChunkDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {

    /** 自定义批量 SQL 显式指定 pgvector TypeHandler；ID、计数、审计字段由 Service 填充。 */
    @Insert("""
            <script>
            INSERT INTO t_knowledge_chunk
                (id, kb_id, collection, doc_id, chunk_index, content, embedding,
                 char_count, token_count, create_time, update_time, create_by, update_by, enabled, deleted)
            VALUES
            <foreach collection="chunks" item="chunk" separator=",">
                (#{chunk.id}, #{chunk.kbId}, #{chunk.collection}, #{chunk.docId}, #{chunk.chunkIndex},
                 #{chunk.content},
                 #{chunk.embedding,jdbcType=OTHER,typeHandler=com.llll.rag.knowledge.dao.handler.VectorTypeHandler},
                 #{chunk.charCount}, #{chunk.tokenCount}, #{chunk.createTime}, #{chunk.updateTime},
                 #{chunk.createBy}, #{chunk.updateBy}, #{chunk.enabled}, #{chunk.deleted})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("chunks") List<KnowledgeChunkDO> chunks);

    // 删除该文档的全部分块，包含已逻辑删除的分块。
    @Delete("DELETE FROM t_knowledge_chunk WHERE doc_id = #{docId}")
    int physicalDeleteByDocId(@Param("docId") String docId);
}
