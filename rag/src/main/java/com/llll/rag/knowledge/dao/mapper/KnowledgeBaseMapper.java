package com.llll.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llll.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.llll.rag.search.routing.model.KnowledgeBaseMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseDO> {

    /** 当前是共享公共知识库；创建人仅用于审计，不作为检索权限。 */
    @Select("""
            SELECT id AS kb_id, name, description
            FROM t_knowledge_base
            WHERE deleted = 0
            ORDER BY id
            """)
    List<KnowledgeBaseMeta> selectRetrievableMetadata();

    /** 删除恢复使用，必须包含已逻辑删除的 KB，并在业务事务中锁定记录。 */
    @Select("""
            SELECT id, name, collection, embedding_model, create_time, update_time,
                   description,
                   create_by, update_by, deleted
            FROM t_knowledge_base
            WHERE id = #{kbId}
            FOR UPDATE
            """)
    KnowledgeBaseDO selectIncludingDeletedForUpdate(@Param("kbId") String kbId);

    @Update("""
            UPDATE t_knowledge_base
            SET deleted = 1, update_by = #{operator}, update_time = #{now}
            WHERE id = #{kbId} AND deleted = 0
            """)
    int logicalDeleteWithAudit(@Param("kbId") String kbId,
                               @Param("operator") String operator,
                               @Param("now") Date now);

}
