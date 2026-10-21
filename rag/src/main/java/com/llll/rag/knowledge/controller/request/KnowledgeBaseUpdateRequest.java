package com.llll.rag.knowledge.controller.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeBaseUpdateRequest {

    private String name;

    /** null 保留原描述，空字符串清空描述。 */
    private String description;

}
