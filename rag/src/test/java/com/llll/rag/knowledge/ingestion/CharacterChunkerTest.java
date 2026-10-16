package com.llll.rag.knowledge.ingestion;

import com.llll.rag.ingestion.chunker.CharacterChunker;
import com.llll.rag.ingestion.chunker.properties.ChunkingProperties;
import com.llll.rag.ingestion.model.DocumentBlock;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CharacterChunkerTest {

    private final CharacterChunker chunker = new CharacterChunker(new ChunkingProperties());

    @Test
    void titleContextDoesNotLeakToTheNextChapter() {
        var chunks = chunker.chunk(List.of(new DocumentBlock(0, 1, "Redis", "缓存。"),
                new DocumentBlock(1, 2, "数据结构", "支持 String。"),
                new DocumentBlock(2, 1, "MySQL", "数据库。")));
        assertThat(chunks).extracting("content").containsExactly("Redis\n\n缓存。",
                "Redis\n数据结构\n\n支持 String。", "MySQL\n\n数据库。");
        assertThat(chunks).extracting("index").containsExactly(0, 1, 2);
    }

    @Test
    void paragraphMergingTargets600WithoutOverlapOrMissingSeparators() {
        String body = "a".repeat(280) + "\n\n" + "b".repeat(280) + "\n\n" + "c".repeat(280);
        var chunks = chunker.chunk(List.of(new DocumentBlock(0, 1, "T", body)));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.stream().map(chunk -> chunk.getContent().substring(3)).reduce("", String::concat))
                .isEqualTo(body);
        assertThat(length(chunks.getFirst().getContent())).isLessThanOrEqualTo(600);
        var complete = chunker.chunk(List.of(new DocumentBlock(0, 1, "T", "文".repeat(900))));
        assertThat(complete).hasSize(1);
        assertThat(length(complete.getFirst().getContent())).isEqualTo(903);
    }

    @Test
    void forcedSplitsPreserveUnicodeWith80CharacterOverlap() {
        var source = new StringBuilder();
        for (int index = 0; index < 2500; index++) {
            source.appendCodePoint(0x1f300 + index % 400);
        }
        var chunks = chunker.chunk(List.of(new DocumentBlock(0, 1, "T", source.toString())));
        assertThat(chunks.size()).isGreaterThan(1);
        var reconstructed = new StringBuilder(chunks.getFirst().getContent().substring(3));
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            assertThat(chunk.getIndex()).isEqualTo(index);
            assertThat(length(chunk.getContent())).isLessThanOrEqualTo(1000);
            assertValidSurrogates(chunk.getContent());
            if (index > 0) {
                String previous = chunks.get(index - 1).getContent().substring(3);
                String current = chunk.getContent().substring(3);
                String tail = previous.substring(previous.offsetByCodePoints(0, length(previous) - 80));
                int afterOverlap = current.offsetByCodePoints(0, 80);
                assertThat(current.substring(0, afterOverlap)).isEqualTo(tail);
                reconstructed.append(current.substring(afterOverlap));
            }
        }
        assertThat(reconstructed.toString()).isEqualTo(source.toString());
    }

    @Test
    void overlapDoesNotCrossParagraphsAndInvalidBudgetsFail() {
        String body = "甲".repeat(1700) + "\n\n" + "乙".repeat(120);
        var chunks = chunker.chunk(List.of(new DocumentBlock(0, 1, "T", body)));
        assertThat(chunks.getLast().getContent()).isEqualTo("T\n\n" + "乙".repeat(120));
        var properties = new ChunkingProperties();
        properties.setMaxCharacters(500);
        assertThatThrownBy(() -> new CharacterChunker(properties)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.chunk(List.of(new DocumentBlock(0, 1, "标题".repeat(500), "正文"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.chunk(List.of(new DocumentBlock(0, 0, "", "  "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private int length(String text) { return text.codePointCount(0, text.length()); }

    private void assertValidSurrogates(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                assertThat(index + 1).isLessThan(text.length());
                assertThat(Character.isLowSurrogate(text.charAt(++index))).isTrue();
            } else {
                assertThat(Character.isLowSurrogate(value)).isFalse();
            }
        }
    }
}
