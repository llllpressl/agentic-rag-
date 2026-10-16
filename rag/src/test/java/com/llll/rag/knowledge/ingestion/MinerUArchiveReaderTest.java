package com.llll.rag.knowledge.ingestion;

import com.llll.rag.ingestion.parser.mineru.MinerUArchiveReader;
import com.llll.rag.ingestion.parser.mineru.MinerUProperties;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class MinerUArchiveReaderTest {

    @Test
    void readsMarkdownWithoutExtractingPathsToDisk() throws Exception {
        var reader = new MinerUArchiveReader(new MinerUProperties());
        byte[] bytes = zip(Map.of("nested/full.md", "# 正文\n内容".getBytes(StandardCharsets.UTF_8),
                "../ignored.json", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(reader.readMarkdown(new ByteArrayInputStream(bytes))).isEqualTo("# 正文\n内容");
    }

    @Test
    void missingAmbiguousAndMalformedResultsFail() throws Exception {
        var reader = new MinerUArchiveReader(new MinerUProperties());
        byte[] missing = zip(Map.of("images/page.png", new byte[]{1}));
        byte[] duplicate = zip(Map.of("a/full.md", new byte[]{65}, "b/full.md", new byte[]{66}));
        byte[] malformed = zip(Map.of("full.md", new byte[]{(byte) 0xc3, 0x28}));
        assertThatThrownBy(() -> reader.readMarkdown(new ByteArrayInputStream(missing)))
                .isInstanceOf(IOException.class).hasMessageContaining("缺少 full.md");
        assertThatThrownBy(() -> reader.readMarkdown(new ByteArrayInputStream(duplicate)))
                .isInstanceOf(IOException.class).hasMessageContaining("多个 full.md");
        assertThatThrownBy(() -> reader.readMarkdown(new ByteArrayInputStream(malformed)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void markdownAndUncompressedLimitsAreEnforced() throws Exception {
        var properties = new MinerUProperties();
        properties.setMaxMarkdownBytes(3);
        var reader = new MinerUArchiveReader(properties);
        byte[] tooLong = zip(Map.of("full.md", "正文太长".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> reader.readMarkdown(new ByteArrayInputStream(tooLong)))
                .isInstanceOf(IOException.class).hasMessageContaining("Markdown 正文超出上限");
        properties.setMaxMarkdownBytes(100);
        properties.setMaxUncompressedBytes(3);
        byte[] otherEntry = zip(Map.of("full.md", new byte[]{65}, "image.bin", new byte[10]));
        assertThatThrownBy(() -> reader.readMarkdown(new ByteArrayInputStream(otherEntry)))
                .isInstanceOf(IOException.class).hasMessageContaining("解压总量超出上限");
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
