package com.llll.rag.ingestion.parser.mineru;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 在流中读取唯一 full.md，不将 ZIP 路径解压到本地文件系统。 */
@Component
public class MinerUArchiveReader {

    private final MinerUProperties properties;

    public MinerUArchiveReader(MinerUProperties properties) {
        this.properties = properties;
    }

    public String readMarkdown(InputStream stream) throws IOException {
        byte[] markdown = null;
        long uncompressed = 0;
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new LimitedArchiveStream(stream, properties.getMaxArchiveBytes()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                String basename = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                boolean selected = !entry.isDirectory() && "full.md".equals(basename);
                if (selected && markdown != null) {
                    throw new IOException("MinerU 结果包含多个 full.md，无法确定完整正文");
                }
                var output = selected ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    uncompressed += count;
                    if (uncompressed > properties.getMaxUncompressedBytes()) {
                        throw new IOException("MinerU 结果解压总量超出上限");
                    }
                    if (selected) {
                        if ((long) output.size() + count > properties.getMaxMarkdownBytes()) {
                            throw new IOException("MinerU Markdown 正文超出上限");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                if (selected) {
                    markdown = output.toByteArray();
                }
                zip.closeEntry();
            }
        }
        if (markdown == null) {
            throw new IOException("MinerU 结果缺少 full.md");
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(markdown)).toString();
    }

    private static final class LimitedArchiveStream extends FilterInputStream {

        private final long maximum;
        private long consumed;

        private LimitedArchiveStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) {
                checkLimit(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count > 0) {
                checkLimit(count);
            }
            return count;
        }

        private void checkLimit(int count) throws IOException {
            consumed += count;
            if (consumed > maximum) {
                throw new IOException("MinerU ZIP 下载量超出上限");
            }
        }
    }
}
