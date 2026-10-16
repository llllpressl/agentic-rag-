package com.llll.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDocDO> {

    // 逻辑删除与审计字段在同一条 SQL 中更新。
    @Update("""
            UPDATE t_knowledge_document
            SET deleted = 1, update_by = #{updateBy}, update_time = #{updateTime}
            WHERE id = #{docId} AND deleted = 0 AND status <> 'running'
            """)
    int logicalDeleteWithAudit(@Param("docId") String docId,
                               @Param("updateBy") String updateBy,
                               @Param("updateTime") Date updateTime);
}
