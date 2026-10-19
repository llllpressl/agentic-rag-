package com.llll.rag.ingestion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    /** Markdown 标题等级 1 到 6；无标题的正文块为 0。 */
    private Integer level;

    /** 原始 Markdown 标题切片，保留标题标记与换行；无标题时使用空字符串。 */
    private String title;

    /** 标题之后到下一标题之前的原始正文，保留空行、缩进及换行，不重复存放 title。 */
    private String content;
}
