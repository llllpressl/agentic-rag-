package com.llll.rag.search.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** query 已由调用方完成语义增强，搜索阶段不再改写问题。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchRequest {

    private String query;
}
