package com.llll.rag.ingestion.chunker.count.countImpl;

import com.llll.rag.ingestion.chunker.count.TokenCounter;
import org.springframework.stereotype.Component;

/** 模拟实现 tokenizer。
 * 按照 1.5倍估算，英文会高估
 * TODO 这里Tokenizer可以换成更好的
 * */
@Component
public class EstimatedTokenCounter implements TokenCounter {

    @Override
    public int count(String text) {
        if (text == null) {
            throw new IllegalArgumentException("计数文本不能为空");
        }

        int characters = text.codePointCount(0, text.length());
        return (int) Math.ceil(characters * 1.5);
    }
}