package com.llll.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llll.rag.knowledge.dao.entity.KnowledgeChunkDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {

    @Update("""
            UPDATE t_knowledge_chunk
            SET deleted = 1, update_by = #{operator}, update_time = #{now}
            WHERE kb_id = #{kbId} AND deleted = 0
            """)
    int logicalDeleteByKbId(@Param("kbId") String kbId,
                            @Param("operator") String operator,
                            @Param("now") Date now);

    /** 专用于已删除知识库的残余清理，不经过 MyBatis-Plus 逻辑删除。 */
    @Delete("DELETE FROM t_knowledge_chunk WHERE kb_id = #{kbId}")
    int physicalDeleteByKbId(@Param("kbId") String kbId);

    /** 按文档批量物理删除分块及向量，不经过 MyBatis-Plus 逻辑删除。 */
    @Delete("DELETE FROM t_knowledge_chunk WHERE doc_id = #{docId}")
    int physicalDeleteByDocId(@Param("docId") String docId);

}
