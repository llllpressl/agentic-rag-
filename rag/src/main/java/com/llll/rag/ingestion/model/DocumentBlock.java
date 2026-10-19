package com.llll.rag.ingestion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 解析得到的结构块，不是用于入库的检索分块。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentBlock {

    /** 文档内结构块序号，从 0 开始。 */
    private Integer index;

    /** Markdown 标题等级 1 到 6；非全局标题块为 0。 */
    private Integer level;

    /** 原约定：原始 Markdown 标题切片，保留标题标记与换行。 */
    /** 当前为可读标题文本；标题等级由 level 保留，其他块使用空字符串。 */
    private String title;

    /** 原约定：标题之后到下一标题之前的原始正文切片。 */
    /** 当前为本结构块的正文；代码和 RAW 内容不做普通正文清洗。 */
    private String content;

    private DocumentBlockType type;

    /** 列表、列表项及引用内部的结构块；不将其中的标题提升为全局章节。 */
    private List<DocumentBlock> children;

    /** 列表项的原始项目符或序号，例如 "- "、"3. "。 */
    private String marker;

    /** 表格的规范化表头，包含列分隔行；长表格拆分时重复使用。 */
    private String tableHeader;

    /** 表格数据行，不包含表头，每行只参与一次正文分配。 */
    private List<String> rows;

    /** 代码围栏中的语言及附加信息，正文保存在 content 中。 */
    private String language;
}
