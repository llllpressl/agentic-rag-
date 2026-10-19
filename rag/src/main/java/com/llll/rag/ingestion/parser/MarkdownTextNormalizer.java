package com.llll.rag.ingestion.parser;

import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentBlockType;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;

import java.util.List;

/** 按 AST 去除装饰；结构渲染同时供 Parser 和 Chunker 使用，避免两套长度口径。 */
public final class MarkdownTextNormalizer {

    private MarkdownTextNormalizer() {
    }

    public static String prepareSource(String source) {
        String text = source;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String inlineText(Node parent) {
        StringBuilder text = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            appendInline(child, text);
        }
        return text.toString().strip();
    }

    private static void appendInline(Node node, StringBuilder text) {
        if (node instanceof Text) {
            // 只压缩 AST 普通文字中的水平空白，代码节点不经过这条规则。
            text.append(((Text) node).getLiteral().replaceAll("[\\t ]+", " "));
        } else if (node instanceof Code) {
            String literal = ((Code) node).getLiteral();
            String delimiter = "`".repeat(longestRun(literal, '`') + 1);
            String padding = "";
            if (literal.startsWith("`") || literal.endsWith("`")
                    || (literal.startsWith(" ") && literal.endsWith(" ") && !literal.isBlank())) {
                padding = " ";
            }
            // 必要时补分隔空格，避免反引号相连；不改变纯空格代码跨度的实际内容。
            text.append(delimiter).append(padding).append(literal).append(padding).append(delimiter);
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            text.append('\n');
        } else if (node instanceof HtmlInline) {
            text.append(((HtmlInline) node).getLiteral());
        } else if (node instanceof Link || node instanceof Image) {
            String label = inlineText(node);
            String destination;
            String title;
            if (node instanceof Link) {
                destination = ((Link) node).getDestination();
                title = ((Link) node).getTitle();
            } else {
                destination = ((Image) node).getDestination();
                title = ((Image) node).getTitle();
            }
            text.append(label);
            if (destination != null && !destination.isBlank() && !destination.equals(label)) {
                text.append(" (").append(destination).append(')');
            }
            if (title != null && !title.isBlank() && !title.equals(label)) {
                text.append(" (").append(title).append(')');
            }
        } else {
            // Emphasis、StrongEmphasis 等只移除装饰，子节点中的实际文字继续保留。
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                appendInline(child, text);
            }
        }
    }

    public static String render(DocumentBlock block) {
        DocumentBlockType type = block.getType();
        if (type == DocumentBlockType.CODE_BLOCK) {
            return renderCode(block, block.getContent());
        }
        if (type == DocumentBlockType.TABLE) {
            return renderTable(block, block.getRows());
        }
        if (type == DocumentBlockType.LIST || type == DocumentBlockType.LIST_ITEM
                || type == DocumentBlockType.QUOTE) {
            String separator = "\n\n";
            if (type == DocumentBlockType.LIST) {
                separator = "\n";
            }
            StringBuilder body = new StringBuilder();
            for (DocumentBlock child : block.getChildren()) {
                if (!body.isEmpty()) {
                    body.append(separator);
                }
                body.append(render(child));
            }
            return wrapContainer(block, body.toString());
        }
        if (type == DocumentBlockType.HEADING) {
            return block.getTitle();
        }
        return block.getContent();
    }

    public static String wrapContainer(DocumentBlock container, String body) {
        if (container.getType() == DocumentBlockType.LIST) {
            return body;
        }
        StringBuilder result = new StringBuilder();
        String[] lines = body.split("\n", -1);
        String marker = "> ";
        if (container.getType() == DocumentBlockType.LIST_ITEM) {
            marker = container.getMarker();
        }
        String indentation = " ".repeat(marker.codePointCount(0, marker.length()));
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                result.append('\n');
            }
            if (container.getType() == DocumentBlockType.QUOTE || index == 0) {
                result.append(marker);
            } else {
                result.append(indentation);
            }
            result.append(lines[index]);
        }
        return result.toString();
    }

    public static String renderCode(DocumentBlock block, String body) {
        return renderCode(block, body, codeFence(block));
    }

    public static String codeFence(DocumentBlock block) {
        String fence = "`".repeat(Math.max(3, longestRun(block.getContent(), '`') + 1));
        // 带反引号的 info 在反引号围栏中无效，改用足够长的波浪线围栏。
        if (block.getLanguage().contains("`")) {
            fence = "~".repeat(Math.max(3, longestRun(block.getContent(), '~') + 1));
        }
        return fence;
    }

    public static String renderCode(DocumentBlock block, String body, String fence) {
        String ending = "\n";
        if (body.endsWith("\n")) {
            ending = "";
        }
        return fence + block.getLanguage() + "\n" + body + ending + fence;
    }

    public static String renderTable(DocumentBlock block, List<String> rows) {
        StringBuilder table = new StringBuilder(block.getTableHeader());
        for (String row : rows) {
            table.append('\n').append(row);
        }
        return table.toString();
    }

    private static int longestRun(String text, char character) {
        int maximum = 0;
        int current = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == character) {
                current++;
                maximum = Math.max(maximum, current);
            } else {
                current = 0;
            }
        }
        return maximum;
    }
}
