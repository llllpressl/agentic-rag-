package com.llll.rag.knowledge.ingestion;

import com.llll.rag.ingestion.parser.MarkdownDocumentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    void sectionsPreserveCodeQuotesAndTables() {
        String markdown = """
                前言。

                # Redis
                Redis 是缓存。

                ```markdown
                # 这是代码中的标题
                ```

                > # 引用中的标题

                | 类型 | 说明 |
                | --- | --- |
                | String | 字符串 |

                ## 数据结构
                支持 String。
                """;
        var blocks = parser.parseMarkdown(markdown).getBlocks();
        assertThat(blocks).hasSize(3);
        assertThat(blocks).extracting("index").containsExactly(0, 1, 2);
        assertThat(blocks).extracting("level").containsExactly(0, 1, 2);
        assertThat(blocks).extracting("title").containsExactly("", "Redis", "数据结构");
        assertThat(blocks.get(0).getContent()).isEqualTo("前言。");
        assertThat(blocks.get(1).getContent()).contains("```markdown\n# 这是代码中的标题\n```",
                "> # 引用中的标题", "| String | 字符串 |");
        assertThat(blocks.get(2).getContent()).isEqualTo("支持 String。");
    }

    @Test
    void handlesSetextBomCrLfAndKeepsCodeIndentation() {
        var blocks = parser.parseMarkdown("\uFEFFRedis\r\n=====\r\n\r\n正文。\r\n").getBlocks();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().getTitle()).isEqualTo("Redis");
        assertThat(blocks.getFirst().getLevel()).isEqualTo(1);
        assertThat(blocks.getFirst().getContent()).isEqualTo("正文。");
        String body = "    value = 42\n\n<table><tr><td>价格</td><td>1999</td></tr></table>";
        assertThat(parser.parseMarkdown("# 参数\n\n" + body + "\n\n").getBlocks().getFirst().getContent())
                .isEqualTo(body);
    }

    @Test
    void invalidUtf8AndEmptyDocumentsFail(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("broken.md");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> parser.parse(file.toFile())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parser.parseMarkdown(" \n\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseMarkdown("\uFEFF")).isInstanceOf(IllegalArgumentException.class);
    }
}
