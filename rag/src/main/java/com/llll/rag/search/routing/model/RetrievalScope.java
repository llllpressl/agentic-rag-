package com.llll.rag.search.routing.model;

import lombok.Builder;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.List;

/** unrestricted 仅用于关闭路由时的旧行为；启用后的 GLOBAL 仍限定在可用库集合。 */
@Getter
@Builder
public class RetrievalScope {

    private final boolean unrestricted;

    private final List<String> kbIds;

    public void validate() {
        if (kbIds == null) {
            throw new IllegalArgumentException("检索范围的知识库集合不能为 null");
        }
        if (unrestricted && !kbIds.isEmpty()) {
            throw new IllegalArgumentException("不限制范围时不能同时指定知识库");
        }
        for (String kbId : kbIds) {
            if (!StringUtils.hasText(kbId)) {
                throw new IllegalArgumentException("检索范围不能包含空知识库 ID");
            }
        }
    }
}
