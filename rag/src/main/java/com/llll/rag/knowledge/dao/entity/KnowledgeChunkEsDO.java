package com.llll.rag.knowledge.dao.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KnowledgeChunkEsDO {

    // 所属知识库，用于过滤检索范围
    private String kbId;

    // 所属文档，用于按文档同步或删除索引
    private String docId;

    // BM25 关键词检索内容
    private String content;

    // 是否启用
    private Integer enabled;

    // 是否删除
    private Integer deleted;

    /// 这里面没有写chunk_id 是为了让chunk_id 做es里的 _id
}
