package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.chunker.properties.ChunkingProperties;
import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
/** import java.util.TreeMap; */
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 字符预算下优先保持段落，超长段落再按句子、行或字符切分。 */
@Component
public class CharacterChunker implements Chunker {

    /** 原换行匹配规则：
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\n[\t ]*\n(?:[\t ]*\n)*");
    */
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

    /**
     * 原实现：按 Block 独立分块，补入祖先标题并重复超长正文片段。
    @Override
    public List<DocumentChunk> chunk(List<DocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("待分块的 Block 不能为空");
        }
        var chunks = new ArrayList<DocumentChunk>();
        var headings = new TreeMap<Integer, String>();
        for (DocumentBlock block : blocks) {
            if (block == null || block.getLevel() == null || block.getLevel() < 0 || block.getLevel() > 6) {
                throw new IllegalArgumentException("Block 标题等级必须在 0 到 6 之间");
            }
            int level = block.getLevel();
            String title = block.getTitle() == null ? "" : block.getTitle();
            String body = block.getContent() == null ? "" : block.getContent();
            if (level == 0) {
                headings.clear();
            } else {
                headings.tailMap(level, true).clear();
                if (!title.isBlank()) {
                    headings.put(level, title);
                }
            }
            String context = String.join("\n", headings.values());
            if (level == 0 && !title.isBlank()) {
                context = title;
            }
            if (body.isBlank()) {
                if (!title.isBlank()) {
                    emit(chunks, context, "");
                }
                continue;
            }
            String prefix = context.isBlank() ? "" : context + "\n\n";
            int prefixLength = length(prefix);
            int hardBudget = maximum - prefixLength;
            if (hardBudget <= 0) {
                throw new IllegalArgumentException("标题上下文占满分块上限，无法保留正文");
            }
            int softBudget = Math.max(1, target - prefixLength);
            splitBody(chunks, prefix, body, softBudget, hardBudget);
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("分块结果为空");
        }
        return List.copyOf(chunks);
    }

    private void splitBody(List<DocumentChunk> chunks, String prefix, String body,
                           int softBudget, int hardBudget) {
        var buffer = new StringBuilder();
        int bufferedLength = 0;
        for (String paragraph : paragraphs(body)) {
            int paragraphLength = length(paragraph);
            if (bufferedLength > 0 && bufferedLength + paragraphLength > softBudget) {
                emit(chunks, prefix, buffer.toString());
                buffer.setLength(0);
                bufferedLength = 0;
            }
            if (paragraphLength > hardBudget) {
                splitLongParagraph(chunks, prefix, paragraph, softBudget, hardBudget);
            } else {
                buffer.append(paragraph);
                bufferedLength += paragraphLength;
            }
        }
        if (bufferedLength > 0) {
            emit(chunks, prefix, buffer.toString());
        }
    }

    private List<String> paragraphs(String body) {
        var parts = new ArrayList<String>();
        var matcher = PARAGRAPH_BREAK.matcher(body);
        int start = 0;
        while (matcher.find()) {
            // 分隔符随前一段保留，合并时不添加或丢弃原有字符。
            parts.add(body.substring(start, matcher.end()));
            start = matcher.end();
        }
        if (start < body.length()) {
            parts.add(body.substring(start));
        }
        return parts;
    }

    private void splitLongParagraph(List<DocumentChunk> chunks, String prefix, String paragraph,
                                    int softBudget, int hardBudget) {
        int[] points = paragraph.codePoints().toArray();
        int start = 0;
        while (start < points.length) {
            int end;
            if (points.length - start <= hardBudget) {
                end = points.length;
            } else {
                end = preferredEnd(points, start, softBudget, hardBudget);
            }
            emit(chunks, prefix, new String(points, start, end - start));
            if (end == points.length) {
                break;
            }
            // 正文有足够空间时使用 80；小预算时缩短重叠，始终保证前进。
            int repeated = Math.min(overlap, Math.max(0, (end - start) / 2));
            start = end - repeated;
        }
    }

    private int preferredEnd(int[] points, int start, int softBudget, int hardBudget) {
        int desired = start + Math.min(softBudget, hardBudget);
        int lower = start + Math.max(1, Math.min(softBudget, hardBudget) / 2);
        int upper = Math.min(points.length, start + hardBudget);
        // 句子边界优先，其次完整行，最后词边界；均在目标附近选择。
        for (int kind = 0; kind < 3; kind++) {
            int best = -1;
            for (int end = lower; end <= upper; end++) {
                if (isBoundary(points, end, kind)
                        && (best < 0 || Math.abs(end - desired) < Math.abs(best - desired))) {
                    best = end;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return desired;
    }

    private boolean isBoundary(int[] points, int end, int kind) {
        int previous = points[end - 1];
        if (kind == 1) {
            return previous == '\n';
        }
        if (kind == 2) {
            return Character.isWhitespace(previous);
        }
        if ("。！？；".indexOf(previous) >= 0) {
            return true;
        }
        return ".!?;".indexOf(previous) >= 0
                && (end == points.length || Character.isWhitespace(points[end]));
    }

    private void emit(List<DocumentChunk> chunks, String prefix, String body) {
        String content = prefix + body;
        if (content.isBlank()) {
            return;
        }
        if (length(content) > maximum) {
            throw new IllegalArgumentException("分块文本超出字符上限");
        }
        chunks.add(new DocumentChunk(chunks.size(), content));
    }

    private int length(String text) {
        return text.codePointCount(0, text.length());
    }
    */

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
