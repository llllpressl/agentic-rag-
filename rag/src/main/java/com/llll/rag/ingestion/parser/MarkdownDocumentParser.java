package com.llll.rag.ingestion.parser;

import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentBlockType;
import com.llll.rag.ingestion.model.ParseResult;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 原实现只识别标题并按源位置切片；现按 AST 提取结构及规范化正文。 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

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

    public ParseResult parseMarkdown(String markdown) {
        if (markdown == null) {
            throw new IllegalArgumentException("解析正文为空");
        }
        String source = MarkdownTextNormalizer.prepareSource(markdown);
        if (source.isBlank()) {
            throw new IllegalArgumentException("解析正文为空");
        }
        Node root = parser.parse(source);
        List<DocumentBlock> blocks = parseChildren(root, source, true);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("文档没有可用于分块的标题或正文");
        }
        return ParseResult.builder()
                .blocks(List.copyOf(blocks))
                .build();
    }

    private List<DocumentBlock> parseChildren(Node parent, String source, boolean globalHeadings) {
        List<DocumentBlock> blocks = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            DocumentBlock block = parseBlock(node, source, globalHeadings, blocks.size());
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private DocumentBlock parseBlock(Node node, String source, boolean globalHeadings, int index) {
        if (node instanceof ThematicBreak) {
            // 水平分隔线只是装饰，不生成只有 "---" 的检索块。
            return null;
        }
        if (node instanceof Heading && globalHeadings) {
            String title = MarkdownTextNormalizer.inlineText(node);
            if (title.isBlank()) {
                return null;
            }
            return DocumentBlock.builder()
                    .index(index)
                    .type(DocumentBlockType.HEADING)
                    .level(((Heading) node).getLevel())
                    .title(title)
                    .content("")
                    .build();
        }
        if (node instanceof Paragraph || node instanceof Heading) {
            String body = MarkdownTextNormalizer.inlineText(node);
            if (body.isBlank()) {
                return null;
            }
            return textBlock(index, DocumentBlockType.PARAGRAPH, body);
        }
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            String body;
            String language = "";
            if (node instanceof FencedCodeBlock) {
                FencedCodeBlock code = (FencedCodeBlock) node;
                body = code.getLiteral();
                if (code.getInfo() != null) {
                    language = code.getInfo().strip();
                }
            } else {
                body = ((IndentedCodeBlock) node).getLiteral();
            }
            if (body.isBlank()) {
                return null;
            }
            return DocumentBlock.builder()
                    .index(index)
                    .type(DocumentBlockType.CODE_BLOCK)
                    .level(0)
                    .title("")
                    .content(body)
                    .language(language)
                    .build();
        }
        if (node instanceof TableBlock) {
            return parseTable(node, source, index);
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            List<DocumentBlock> items = new ArrayList<>();
            for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                List<DocumentBlock> children = parseChildren(item, source, false);
                if (!children.isEmpty()) {
                    items.add(DocumentBlock.builder()
                            .index(items.size())
                            .type(DocumentBlockType.LIST_ITEM)
                            .level(0)
                            .title("")
                            .content("")
                            .marker(readListMarker(item, source))
                            .children(List.copyOf(children))
                            .build());
                }
            }
            return containerBlock(index, DocumentBlockType.LIST, items);
        }
        if (node instanceof BlockQuote || node instanceof ListItem) {
            return containerBlock(index, DocumentBlockType.QUOTE, parseChildren(node, source, false));
        }
        if (node instanceof HtmlBlock) {
            return textBlock(index, DocumentBlockType.RAW, ((HtmlBlock) node).getLiteral());
        }
        // HTML、引用定义和暂未专门识别的结构保留源文本，不以空串兜底。
        return textBlock(index, DocumentBlockType.RAW, sourceSlice(node, source));
    }

    private DocumentBlock containerBlock(int index, DocumentBlockType type, List<DocumentBlock> children) {
        if (children.isEmpty()) {
            return null;
        }
        return DocumentBlock.builder()
                .index(index)
                .type(type)
                .level(0)
                .title("")
                .content("")
                .children(List.copyOf(children))
                .build();
    }

    private DocumentBlock textBlock(int index, DocumentBlockType type, String content) {
        if (content.isBlank()) {
            return null;
        }
        return DocumentBlock.builder()
                .index(index)
                .type(type)
                .level(0)
                .title("")
                .content(content)
                .build();
    }

    private String readListMarker(Node item, String source) {
        String original = sourceSlice(item, source).stripLeading();
        Matcher matcher = Pattern.compile("^([0-9]+[.)]|[-+*])(?:[ \\t]+|$)").matcher(original);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Markdown 列表项缺少可识别的项目符或序号");
        }
        return matcher.group(1) + " ";
    }

    private DocumentBlock parseTable(Node table, String source, int index) {
        String header = null;
        List<String> rows = new ArrayList<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) {
                    throw new IllegalArgumentException("Markdown 表格包含无法识别的行结构，Block=" + index);
                }
                StringBuilder rendered = new StringBuilder("|");
                StringBuilder separator = new StringBuilder("|");
                int cellCount = 0;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    cellCount++;
                    String value = MarkdownTextNormalizer.inlineText(cell)
                            .replace("\\", "\\\\")
                            .replace("|", "\\|")
                            .replace("\n", "<br>");
                    rendered.append(' ').append(value).append(" |");
                    separator.append(" --- |");
                }
                // GFM 会忽略比表头更多的数据列；保守保留整张原表，避免 AST 重建静默丢掉这些值。
                if (sourceCellCount(sourceSlice(row, source)) > cellCount) {
                    return textBlock(index, DocumentBlockType.RAW, sourceSlice(table, source));
                }
                if (section instanceof TableHead) {
                    header = rendered + "\n" + separator;
                } else {
                    rows.add(rendered.toString());
                }
            }
        }
        if (header == null) {
            throw new IllegalArgumentException("Markdown 表格缺少表头，Block=" + index);
        }
        return DocumentBlock.builder()
                .index(index)
                .type(DocumentBlockType.TABLE)
                .level(0)
                .title("")
                .content("")
                .tableHeader(header)
                .rows(List.copyOf(rows))
                .build();
    }

    private int sourceCellCount(String row) {
        String line = row.strip();
        int start = 0;
        if (line.startsWith("|")) {
            start = 1;
        }
        int count = 0;
        int cellStart = start;
        for (int index = start; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\\' && index + 1 < line.length() && line.charAt(index + 1) == '|') {
                index++;
            } else if (character == '|') {
                count++;
                cellStart = index + 1;
            }
        }
        if (cellStart < line.length()) {
            count++;
        }
        return count;
    }

    private String sourceSlice(Node node, String source) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("Markdown 结构缺少源位置：" + node.getClass().getSimpleName());
        }
        int start = spans.getFirst().getInputIndex();
        SourceSpan last = spans.getLast();
        return source.substring(start, last.getInputIndex() + last.getLength());
    }

    /** 历史原文切片实现，结构分块不再调用。内层注释结束符写作 * / 以保留原代码。
    /** 标题和正文均按原始字符串的位置切片，保留 Markdown 标记、换行和空行。 * /
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
    */
}
