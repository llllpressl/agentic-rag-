package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.parser.MarkdownTextNormalizer;

import java.util.ArrayList;
import java.util.List;

/** 只处理普通正文；标题、代码、表格和列表项边界由调用方识别。 */
final class RecursiveTextSplitter {

    private static final int SEPARATOR_LEVELS = 7;

    List<String> split(String text, int budget, int preferred, List<DocumentBlock> wrappers) {
        return splitRecursively(text, budget, preferred, wrappers, 0);
    }

    private List<String> splitRecursively(String text, int budget, int preferred,
                                          List<DocumentBlock> wrappers, int level) {
        if (measuredLength(text, wrappers) <= budget) {
            return List.of(text);
        }
        if (level == SEPARATOR_LEVELS) {
            return splitCodePoints(text, budget, preferred, wrappers);
        }
        List<String> parts = splitAtBoundaries(text, level);
        if (parts.size() == 1) {
            return splitRecursively(text, budget, preferred, wrappers, level + 1);
        }
        List<String> result = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (String part : parts) {
            if (measuredLength(part, wrappers) > budget) {
                result.addAll(merge(pending, budget, preferred, wrappers));
                pending.clear();
                result.addAll(splitRecursively(part, budget, preferred, wrappers, level + 1));
            } else {
                pending.add(part);
            }
        }
        result.addAll(merge(pending, budget, preferred, wrappers));
        return result;
    }

    /** 分隔符留在前一片段，substring 首尾衔接，不 trim、不补字符。 */
    private List<String> splitAtBoundaries(String text, int level) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int end = 0;
        while (end < text.length()) {
            end += Character.charCount(text.codePointAt(end));
            if (end < text.length() && isBoundary(text, end, level)) {
                parts.add(text.substring(start, end));
                start = end;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private boolean isBoundary(String text, int end, int level) {
        int previous = text.codePointBefore(end);
        int next = text.codePointAt(end);
        if (level == 0) {
            return end >= 2 && text.charAt(end - 1) == '\n'
                    && text.charAt(end - 2) == '\n' && next != '\n';
        }
        if (level == 1) {
            return previous == '\n' && next != '\n';
        }
        if (level == 2) {
            return "。！？".indexOf(previous) >= 0;
        }
        if (level == 3) {
            // 小数、版本号及域名中的点不是句末；英文标点后需要空白或结束引号。
            return ".!?".indexOf(previous) >= 0
                    && (Character.isWhitespace(next) || "\"'”’)]}".indexOf(next) >= 0);
        }
        if (level == 4) {
            return "；，、".indexOf(previous) >= 0;
        }
        if (level == 5) {
            return ";,".indexOf(previous) >= 0 && Character.isWhitespace(next);
        }
        return previous == ' ' || previous == '\t';
    }

    private List<String> merge(List<String> parts, int budget, int preferred,
                                List<DocumentBlock> wrappers) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current + part;
            int currentLength = measuredLength(current.toString(), wrappers);
            int candidateLength = measuredLength(candidate, wrappers);
            boolean nearerTarget = candidateLength > preferred
                    && Math.abs(currentLength - preferred) <= Math.abs(candidateLength - preferred);
            if (!current.isEmpty() && !current.toString().isBlank()
                    && !part.isBlank() && (candidateLength > budget || nearerTarget)) {
                result.add(current.toString());
                current.setLength(0);
            }
            current.append(part);
            if (measuredLength(current.toString(), wrappers) > budget) {
                throw new ChunkBudgetException("普通正文的空白及外层结构无法在预算内完整保留，budget=" + budget);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private List<String> splitCodePoints(String text, int budget, int preferred,
                                          List<DocumentBlock> wrappers) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            String remaining = text.substring(start);
            if (measuredLength(remaining, wrappers) <= budget) {
                result.add(remaining);
                break;
            }
            int lower = 0;
            int upper = Math.min(budget, remaining.codePointCount(0, remaining.length()));
            while (lower < upper) {
                int middle = lower + (upper - lower + 1) / 2;
                int end = text.offsetByCodePoints(start, middle);
                if (measuredLength(text.substring(start, end), wrappers) <= budget) {
                    lower = middle;
                } else {
                    upper = middle - 1;
                }
            }
            if (lower == 0) {
                throw new ChunkBudgetException("外层列表或引用标记没有留下一个 Unicode 码点的正文预算");
            }
            int characters = Math.min(lower, Math.max(1, preferred));
            int end = text.offsetByCodePoints(start, characters);
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }

    static int measuredLength(String text, List<DocumentBlock> wrappers) {
        String rendered = text;
        for (int index = wrappers.size() - 1; index >= 0; index--) {
            rendered = MarkdownTextNormalizer.wrapContainer(wrappers.get(index), rendered);
        }
        return rendered.codePointCount(0, rendered.length());
    }
}
