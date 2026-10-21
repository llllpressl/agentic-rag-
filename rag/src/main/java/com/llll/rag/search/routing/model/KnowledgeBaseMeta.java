package com.llll.rag.search.routing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 只读取路由所需元数据，不读取正文和向量。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseMeta {

    private String kbId;

    private String name;

    private String description;
}
