package com.llll.rag.knowledge.controller.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeBaseCreateRequest {

    private String name;

    private String collection;

    private String embeddingModel;
}
