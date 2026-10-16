package com.llll.rag.ingestion.parser;

import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.ParseResult;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
/** import org.commonmark.renderer.text.TextContentRenderer; */
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 标题由 AST 识别，正文按源位置保留 Markdown，不经 HTML 渲染。 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    /** private final TextContentRenderer titleRenderer = TextContentRenderer.builder().build(); */

    @Override
    public boolean supports(String fileType) {
        return "markdown".equals(fileType) || "md".equals(fileType);
    }

    @Override
    public ParseResult parse(File file) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Markdown 源文件不存在");
        }
        try {
            // readString 对非法 UTF-8 抛异常，避免用替换字符悄悄破坏原文。
            return parseMarkdown(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Markdown 源文件失败", exception);
        }
    }

    /** 原生 Markdown 与 MinerU 的 full.md 复用同一条 Block 构建路径。 */
    /**
     * 原实现：渲染标题并裁掉章节空行，无法完整还原 Markdown 原文。
    public ParseResult parseMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("解析正文为空");
        }
        String source = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        source = source.replace("\r\n", "\n").replace('\r', '\n');
        Node root = parser.parse(source);
        var blocks = new ArrayList<DocumentBlock>();
        int bodyStart = 0;
        int level = 0;
        String title = "";

        // 只以文档顶层标题划分章节；引用、列表及代码内部的内容留在当前正文。
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            if (!(node instanceof Heading heading)) {
                continue;
            }
            var spans = heading.getSourceSpans();
            if (spans.isEmpty()) {
                throw new IllegalStateException("Markdown 标题缺少源位置");
            }
            int headingStart = spans.getFirst().getInputIndex();
            addBlock(blocks, level, title, source.substring(bodyStart, headingStart));
            level = heading.getLevel();
            title = titleRenderer.render(heading).strip();
            var last = spans.getLast();
            bodyStart = last.getInputIndex() + last.getLength();
            if (bodyStart < source.length() && source.charAt(bodyStart) == '\n') {
                bodyStart++;
            }
        }
        addBlock(blocks, level, title, source.substring(bodyStart));
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("文档没有可用于分块的标题或正文");
        }
        return new ParseResult(List.copyOf(blocks));
    }

    private void addBlock(List<DocumentBlock> blocks, int level, String title, String rawBody) {
        String body = trimBlankLines(rawBody);
        if (title.isBlank() && body.isBlank()) {
            return;
        }
        blocks.add(new DocumentBlock(blocks.size(), level, title, body));
    }

    // 只移除章节边缘的空白行，保留代码或列表的首行缩进。
    private String trimBlankLines(String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            int newline = text.indexOf('\n', start);
            int lineEnd = newline < 0 ? end : newline;
            if (!text.substring(start, lineEnd).isBlank()) {
                break;
            }
            start = newline < 0 ? end : newline + 1;
        }
        while (end > start) {
            int newline = text.lastIndexOf('\n', end - 1);
            int lineStart = Math.max(start, newline + 1);
            if (!text.substring(lineStart, end).isBlank()) {
                break;
            }
            end = newline < start ? start : newline;
        }
        // 最后一行有内容时，移除其末尾的换行；不剥离该行的空格。
        return text.substring(start, end);
    }
    */

    /** 标题和正文均按原始字符串的位置切片，保留 Markdown 标记、换行和空行。 */
    public ParseResult parseMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("解析正文为空");
        }
        Node root = parser.parse(markdown);
        List<DocumentBlock> blocks = new ArrayList<>();
        int bodyStart = 0;
        int level = 0;
        String title = "";

        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            if (!(node instanceof Heading)) {
                continue;
            }
            Heading heading = (Heading) node;
            List<SourceSpan> spans = heading.getSourceSpans();
            if (spans.isEmpty()) {
                throw new IllegalStateException("Markdown 标题缺少源位置");
            }
            int headingStart = spans.getFirst().getInputIndex();
            appendSourceBlock(blocks, level, title, markdown.substring(bodyStart, headingStart));

            SourceSpan lastSpan = spans.getLast();
            int headingEnd = lastSpan.getInputIndex() + lastSpan.getLength();
            headingEnd = includeHeadingLineBreak(markdown, headingEnd);
            level = heading.getLevel();
            title = markdown.substring(headingStart, headingEnd);
            bodyStart = headingEnd;
        }
        appendSourceBlock(blocks, level, title, markdown.substring(bodyStart));
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("文档没有可用于分块的标题或正文");
        }
        return ParseResult.builder()
                .blocks(List.copyOf(blocks))
                .build();
    }

    private int includeHeadingLineBreak(String source, int end) {
        if (end < source.length() && source.charAt(end) == '\r') {
            end++;
        }
        if (end < source.length() && source.charAt(end) == '\n') {
            end++;
        }
        return end;
    }

    private void appendSourceBlock(List<DocumentBlock> blocks, int level, String title, String body) {
        // 空白也属于原文，只跳过完全没有字符的结构块。
        if (title.isEmpty() && body.isEmpty()) {
            return;
        }
        blocks.add(DocumentBlock.builder()
                .index(blocks.size())
                .level(level)
                .title(title)
                .content(body)
                .build());
    }
}
