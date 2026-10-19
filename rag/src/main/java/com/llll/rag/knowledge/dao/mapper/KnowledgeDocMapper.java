package com.llll.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDocDO> {

    /** 只取清理需要的字段，包含已逻辑删除的文档，使用 ID 游标分页。 */
    @Select("""
            SELECT id, kb_id, file_url
            FROM t_knowledge_document
            WHERE kb_id = #{kbId}
              AND (CAST(#{afterId} AS TEXT) IS NULL OR id > #{afterId})
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<KnowledgeDocDO> selectCleanupPage(@Param("kbId") String kbId,
                                          @Param("afterId") String afterId,
                                          @Param("limit") int limit);

    @Delete("DELETE FROM t_knowledge_document WHERE kb_id = #{kbId}")
    int physicalDeleteByKbId(@Param("kbId") String kbId);

    // 逻辑删除与审计字段在同一条 SQL 中更新。
    /** 原删除条件：WHERE id = #{docId} AND deleted = 0 AND status <> 'running' */
    @Update("""
            UPDATE t_knowledge_document
            SET deleted = 1, update_by = #{updateBy}, update_time = #{updateTime}
            WHERE id = #{docId} AND deleted = 0 AND status IN ('pending', 'success')
            """)
    int logicalDeleteWithAudit(@Param("docId") String docId,
                               @Param("updateBy") String updateBy,
                               @Param("updateTime") Date updateTime);
}
