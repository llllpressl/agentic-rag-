package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.chunker.properties.ChunkingProperties;
import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 字符预算下优先保持段落，超长段落再按句子、行或字符切分。 */
@Component
public class CharacterChunker implements Chunker {

    private static final String LINE_BREAK = "(?:\\r\\n|\\r(?!\\n)|\\n)";
    private static final Pattern SOURCE_PARAGRAPH_BREAK = Pattern.compile(
            LINE_BREAK + "[\\t ]*" + LINE_BREAK + "(?:[\\t ]*" + LINE_BREAK + ")*");
    private final int target;
    private final int maximum;
    private final int overlap;

    public CharacterChunker(ChunkingProperties properties) {
        target = properties.getTargetCharacters();
        maximum = properties.getMaxCharacters();
        overlap = properties.getOverlapCharacters();
        if (target <= 0 || maximum < target || overlap < 0 || overlap >= target) {
            throw new IllegalArgumentException("分块配置必须满足 0 <= overlap < target <= max");
        }
        if (overlap != 0) {
            throw new IllegalArgumentException("原文保真分块要求 overlap-characters 为 0");
        }
    }

    @Override
    public List<DocumentChunk> chunk(List<DocumentBlock> blocks) {
        String source = joinSourceBlocks(blocks);
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int remainingCharacters = source.codePointCount(0, source.length());
        while (start < source.length()) {
            int end;
            if (remainingCharacters <= maximum) {
                end = source.length();
            } else {
                end = findSourceEnd(source, start, remainingCharacters);
            }
            String content = source.substring(start, end);
            appendSourceChunk(chunks, content);
            remainingCharacters -= content.codePointCount(0, content.length());
            // 下个 Chunk 紧接上个 Chunk，不补标题、不插分隔符、不重复正文。
            start = end;
        }
        return List.copyOf(chunks);
    }

    private String joinSourceBlocks(List<DocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("待分块的 Block 不能为空");
        }
        StringBuilder source = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (block == null || block.getLevel() == null || block.getLevel() < 0 || block.getLevel() > 6) {
                throw new IllegalArgumentException("Block 标题等级必须在 0 到 6 之间");
            }
            // Parser 已保留标题语法和换行，level 只描述结构，不用于生成标题前缀。
            if (block.getTitle() != null) {
                source.append(block.getTitle());
            }
            if (block.getContent() != null) {
                source.append(block.getContent());
            }
        }
        String text = source.toString();
        if (text.isBlank()) {
            throw new IllegalArgumentException("待分块的原文不能为空");
        }
        return text;
    }

    private int findSourceEnd(String source, int start, int remainingCharacters) {
        int minimumCharacters = Math.max(1, target / 2);
        // 为尾段预留空间，避免切到接近硬上限后只剩一个很小的尾块。
        int upperCharacters = Math.min(maximum, remainingCharacters - minimumCharacters);
        int lowerCharacters = Math.min(minimumCharacters, upperCharacters);
        int desiredCharacters = Math.min(target, upperCharacters);
        int lower = source.offsetByCodePoints(start, lowerCharacters);
        int upper = source.offsetByCodePoints(start, upperCharacters);

        int paragraphEnd = findParagraphEnd(source, start, lower, upper, desiredCharacters);
        if (paragraphEnd >= 0) {
            return paragraphEnd;
        }
        // 没有合适的段落边界时，依次寻找句子、完整行和空白边界。
        for (int kind = 0; kind < 3; kind++) {
            int boundaryEnd = findBoundaryEnd(source, start, lower, upper, desiredCharacters, kind);
            if (boundaryEnd >= 0) {
                return boundaryEnd;
            }
        }
        int end = source.offsetByCodePoints(start, desiredCharacters);
        if (splitsLineBreak(source, end)) {
            if (end < upper) {
                end++;
            } else {
                end--;
            }
        }
        return end;
    }

    private int findParagraphEnd(String source, int start, int lower, int upper, int desiredCharacters) {
        Matcher matcher = SOURCE_PARAGRAPH_BREAK.matcher(source);
        matcher.region(start, upper);
        int bestEnd = -1;
        int bestDistance = Integer.MAX_VALUE;
        while (matcher.find()) {
            int end = matcher.end();
            if (end < lower || splitsLineBreak(source, end)) {
                continue;
            }
            int distance = Math.abs(source.codePointCount(start, end) - desiredCharacters);
            if (distance < bestDistance) {
                bestEnd = end;
                bestDistance = distance;
            }
        }
        return bestEnd;
    }

    private int findBoundaryEnd(String source, int start, int lower, int upper,
                                int desiredCharacters, int kind) {
        int bestEnd = -1;
        int bestDistance = Integer.MAX_VALUE;
        int end = lower;
        int characters = source.codePointCount(start, lower);
        while (end <= upper) {
            if (!splitsLineBreak(source, end) && isSourceBoundary(source, end, kind)) {
                int distance = Math.abs(characters - desiredCharacters);
                if (distance < bestDistance) {
                    bestEnd = end;
                    bestDistance = distance;
                }
            }
            if (end == upper) {
                break;
            }
            end += Character.charCount(source.codePointAt(end));
            characters++;
        }
        return bestEnd;
    }

    private boolean isSourceBoundary(String source, int end, int kind) {
        int previous = source.codePointBefore(end);
        if (kind == 1) {
            return previous == '\n' || previous == '\r';
        }
        if (kind == 2) {
            return Character.isWhitespace(previous);
        }
        if ("。！？；".indexOf(previous) >= 0) {
            return true;
        }
        return ".!?;".indexOf(previous) >= 0
                && (end == source.length() || Character.isWhitespace(source.codePointAt(end)));
    }

    private boolean splitsLineBreak(String source, int end) {
        return end > 0 && end < source.length()
                && source.charAt(end - 1) == '\r' && source.charAt(end) == '\n';
    }

    private void appendSourceChunk(List<DocumentChunk> chunks, String content) {
        if (content.isBlank()) {
            throw new IllegalArgumentException("原文存在无法在字符上限内合并的纯空白片段");
        }
        if (content.codePointCount(0, content.length()) > maximum) {
            throw new IllegalArgumentException("分块文本超出字符上限");
        }
        chunks.add(DocumentChunk.builder()
                .index(chunks.size())
                .content(content)
                .build());
    }
}
