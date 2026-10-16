package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentChunk;

import java.util.List;

/**
 * 将结构块转换为符合长度限制的检索文本，不访问模型或存储。
 */
public interface Chunker {

    /**
     * 按输入顺序组装标题和正文；超出上限的内容进入后续 Chunk，不能丢弃。
     * 上限作用于最终 content，包含标题、空格及换行。
     * 输出 index 从 0 连续编号；具体计量方式和上限由后续实现配置。
     */
    List<DocumentChunk> chunk(List<DocumentBlock> blocks);
}
