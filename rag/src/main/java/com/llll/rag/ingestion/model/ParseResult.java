package com.llll.rag.ingestion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 所有解析策略统一输出结构块，按文档阅读顺序排列。
 * PDF/Word 转为 Markdown 属于解析器内部步骤。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

    private List<DocumentBlock> blocks;
}
