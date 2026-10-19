package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentChunk;

import java.util.List;

/**
 * 将结构块转换为符合长度限制的检索文本，不访问模型或存储。
 */
public interface Chunker {

    /**
     * 原约定为按输入顺序拼接原文；当前按结构及章节路径组装规范化正文。
     * 超长内容在所属章节内拆分，允许重复必要标题与表头，实质性正文不能丢失或重叠。
     * 上限作用于最终 content，包含标题、空格及换行。
     * 输出 index 从 0 连续编号；具体计量方式和上限由后续实现配置。
     */
    List<DocumentChunk> chunk(List<DocumentBlock> blocks);
}
