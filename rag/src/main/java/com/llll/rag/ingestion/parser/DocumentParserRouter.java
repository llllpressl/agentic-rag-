package com.llll.rag.ingestion.parser;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/** 基于支持的格式名选择解析策略，不持有文档业务状态。 */
@Component
public class DocumentParserRouter {

    private final List<DocumentParser> parsers;

    public DocumentParserRouter(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public DocumentParser require(String fileType) {
        if (!StringUtils.hasText(fileType)) {
            throw new IllegalArgumentException("文档格式不能为空");
        }
        String normalized = fileType.strip().toLowerCase(Locale.ROOT);
        DocumentParser selected = null;
        for (DocumentParser parser : parsers) {
            if (parser.supports(normalized)) {
                if (selected != null) {
                    throw new IllegalStateException("文档格式匹配到多个解析策略：" + normalized);
                }
                selected = parser;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("不支持分块的文档格式：" + normalized);
        }
        return selected;
    }
}
