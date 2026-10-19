package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.chunker.properties.ChunkingProperties;
import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentBlockType;
import com.llll.rag.ingestion.model.DocumentChunk;
import com.llll.rag.ingestion.parser.MarkdownTextNormalizer;
import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 原全文字符切分实现保留在文末注释中；当前按章节及结构在 Unicode 预算内分块。 */
@Component
public class CharacterChunker implements Chunker {
    private final int target;
    private final int maximum;

    public CharacterChunker(ChunkingProperties properties) {
        target = properties.getTargetCharacters();
        maximum = properties.getMaxCharacters();
        if (target <= 0 || maximum < target || properties.getOverlapCharacters() != 0) {
            throw new IllegalArgumentException("结构分块要求 0 < target <= max，且 overlap-characters 必须为 0");
        }
    }

    @Override
    public List<DocumentChunk> chunk(List<DocumentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("待分块的 Block 不能为空");
        }
        List<Section> sections = collectSections(blocks);
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("文档仅包含标题或空白，无法生成有效正文分块");
        }
        List<SectionChunk> drafts = new ArrayList<>();
        for (Section section : sections) {
            drafts.addAll(splitSection(section));
        }
        return assembleChunks(drafts);
    }

    private List<Section> collectSections(List<DocumentBlock> blocks) {
        List<Section> sections = new ArrayList<>();
        List<DocumentBlock> headings = new ArrayList<>();
        List<DocumentBlock> body = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            validateBlock(block);
            if (block.getType() == DocumentBlockType.HEADING) {
                if (!body.isEmpty()) {
                    sections.add(Section.builder()
                            .headings(List.copyOf(headings))
                            .blocks(List.copyOf(body))
                            .build());
                    body.clear();
                }
                while (!headings.isEmpty() && headings.getLast().getLevel() >= block.getLevel()) {
                    headings.removeLast();
                }
                // 标题只更新上下文；遇到实际正文后才形成可出块的章节。
                headings.add(block);
            } else {
                body.add(block);
            }
        }
        if (!body.isEmpty()) {
            sections.add(Section.builder()
                    .headings(List.copyOf(headings))
                    .blocks(List.copyOf(body))
                    .build());
        }
        return sections;
    }

    private void validateBlock(DocumentBlock block) {
        if (block == null || block.getType() == null || block.getIndex() == null) {
            throw new IllegalArgumentException("Block 缺少序号或结构类型，请使用结构化 Markdown Parser");
        }
        DocumentBlockType type = block.getType();
        if (type == DocumentBlockType.HEADING) {
            if (block.getLevel() == null || block.getLevel() < 1 || block.getLevel() > 6
                    || block.getTitle() == null || block.getTitle().isBlank()) {
                throw new IllegalArgumentException("标题等级必须为 1 到 6，标题文本不能为空，Block=" + block.getIndex());
            }
        } else if (isContainer(type)) {
            if (block.getChildren() == null || block.getChildren().isEmpty()) {
                throw new IllegalArgumentException("结构容器不能为空，Block=" + block.getIndex());
            }
            if (type == DocumentBlockType.LIST_ITEM
                    && (block.getMarker() == null || block.getMarker().isBlank())) {
                throw new IllegalArgumentException("列表项缺少项目符或序号，Block=" + block.getIndex());
            }
            for (DocumentBlock child : block.getChildren()) {
                validateBlock(child);
                if (child.getType() == DocumentBlockType.HEADING) {
                    throw new IllegalArgumentException("容器中的标题不能作为全局章节，Block=" + block.getIndex());
                }
            }
        } else if (type == DocumentBlockType.TABLE) {
            if (block.getTableHeader() == null || block.getTableHeader().isBlank() || block.getRows() == null) {
                throw new IllegalArgumentException("表格缺少表头或数据行集合，Block=" + block.getIndex());
            }
            for (String row : block.getRows()) {
                if (row == null || row.isBlank()) {
                    throw new IllegalArgumentException("表格数据行不能为空，Block=" + block.getIndex());
                }
            }
        } else if (block.getContent() == null || block.getContent().isBlank()) {
            throw new IllegalArgumentException("正文不能为空，Block=" + block.getIndex());
        }
        if (type == DocumentBlockType.CODE_BLOCK && block.getLanguage() == null) {
            throw new IllegalArgumentException("代码语言字段不能为 null，无语言时使用空字符串，Block=" + block.getIndex());
        }
    }

    private List<SectionChunk> splitSection(Section section) {
        List<DocumentBlock> selectedHeadings = new ArrayList<>(section.getHeadings());
        while (true) {
            String headingText = renderHeadings(selectedHeadings);
            int contextLength = length(headingText);
            if (!headingText.isEmpty()) {
                contextLength += 2;
            }
            int budget = maximum - contextLength;
            int preferred = Math.max(1, target - contextLength);
            try {
                if (budget < 1) {
                    throw new BlockBudgetException("标题上下文未给正文留下字符预算");
                }
                List<String> bodies = splitSectionBody(section.getBlocks(), budget, preferred);
                List<SectionChunk> result = new ArrayList<>();
                for (String body : bodies) {
                    result.add(SectionChunk.builder()
                            .headings(List.copyOf(selectedHeadings))
                            .originalHeadings(section.getHeadings())
                            .body(body)
                            .wholeSection(bodies.size() == 1)
                            .build());
                }
                return result;
            } catch (BlockBudgetException exception) {
                if (selectedHeadings.size() > 1) {
                    // 优先移除最远祖先，最具体标题不截断、不移到无正文的独立 Chunk。
                    selectedHeadings.removeFirst();
                } else {
                    throw new IllegalArgumentException("章节无法在 maxCharacters=" + maximum
                            + " 内保留必要上下文及正文：" + renderHeadings(selectedHeadings)
                            + "；" + exception.getMessage(), exception);
                }
            }
        }
    }

    private List<String> splitSectionBody(List<DocumentBlock> blocks, int budget, int preferred) {
        List<String> rendered = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            rendered.add(MarkdownTextNormalizer.render(block));
        }
        String complete = String.join("\n\n", rendered);
        if (length(complete) <= budget) {
            return List.of(complete);
        }
        List<String> pieces = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            pieces.addAll(splitBlock(block, budget, preferred, List.of()));
        }
        return packPieces(pieces, "\n\n", null, List.of(), budget);
    }

    private List<String> splitBlock(DocumentBlock block, int budget, int preferred,
                                    List<DocumentBlock> wrappers) {
        String complete = MarkdownTextNormalizer.render(block);
        if (fits(complete, wrappers, budget)) {
            return List.of(complete);
        }
        DocumentBlockType type = block.getType();
        if (type == DocumentBlockType.PARAGRAPH) {
            return splitParagraph(block, budget, preferred, wrappers);
        }
        if (type == DocumentBlockType.CODE_BLOCK) {
            return splitCode(block, budget, wrappers);
        }
        if (type == DocumentBlockType.TABLE) {
            return splitTable(block, budget, wrappers);
        }
        if (isContainer(type)) {
            List<DocumentBlock> childWrappers = new ArrayList<>(wrappers);
            if (type != DocumentBlockType.LIST) {
                childWrappers.add(block);
            }
            List<String> pieces = new ArrayList<>();
            for (DocumentBlock child : block.getChildren()) {
                pieces.addAll(splitBlock(child, budget, preferred, childWrappers));
            }
            String separator = "\n\n";
            if (type == DocumentBlockType.LIST) {
                separator = "\n";
            }
            return packPieces(pieces, separator, block, wrappers, budget);
        }
        throw budgetFailure(block, "原始 HTML 或未识别结构不能安全拆分", length(complete));
    }

    private List<String> splitParagraph(DocumentBlock block, int budget, int preferred,
                                        List<DocumentBlock> wrappers) {
        String text = block.getContent();
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            String remaining = text.substring(start);
            if (fits(remaining, wrappers, budget)) {
                pieces.add(remaining);
                break;
            }
            int remainingCharacters = length(remaining);
            int lower = 0;
            int upper = Math.min(budget, remainingCharacters);
            // 按实际外层引用符和列表缩进计算可容纳的前缀，不使用估算字符预算。
            while (lower < upper) {
                int middle = lower + (upper - lower + 1) / 2;
                int end = text.offsetByCodePoints(start, middle);
                if (fits(text.substring(start, end), wrappers, budget)) {
                    lower = middle;
                } else {
                    upper = middle - 1;
                }
            }
            if (lower < 1) {
                throw budgetFailure(block, "列表或引用标记后没有正文预算", budget);
            }
            int desired = Math.min(preferred, lower);
            int end = findParagraphBoundary(text, start, lower, desired);
            String piece = text.substring(start, end);
            if (piece.isBlank()) {
                throw budgetFailure(block, "超长纯空白段无法与有效正文合并", length(piece));
            }
            pieces.add(piece);
            start = end;
        }
        return pieces;
    }

    private int findParagraphBoundary(String text, int start, int maximumCharacters, int desired) {
        int minimum = Math.max(1, desired / 2);
        // 完整段落已由 AST 保留；长段落内依次选择句末、其他标点、换行或空白。
        for (int kind = 0; kind < 3; kind++) {
            int bestEnd = -1;
            int bestDistance = Integer.MAX_VALUE;
            int end = start;
            for (int count = 1; count <= maximumCharacters; count++) {
                end += Character.charCount(text.codePointAt(end));
                if (count < minimum || !isParagraphBoundary(text, end, kind)) {
                    continue;
                }
                int distance = Math.abs(count - desired);
                if (distance < bestDistance) {
                    bestEnd = end;
                    bestDistance = distance;
                }
            }
            if (bestEnd >= 0) {
                return bestEnd;
            }
        }
        return text.offsetByCodePoints(start, desired);
    }

    private boolean isParagraphBoundary(String text, int end, int kind) {
        int previous = text.codePointBefore(end);
        if (kind == 2) {
            return Character.isWhitespace(previous);
        }
        if (kind == 1) {
            return "，、,：:；;".indexOf(previous) >= 0;
        }
        if ("。！？".indexOf(previous) >= 0) {
            return true;
        }
        return ".!?".indexOf(previous) >= 0
                && (end == text.length() || Character.isWhitespace(text.codePointAt(end)));
    }

    private List<String> splitCode(DocumentBlock block, int budget, List<DocumentBlock> wrappers) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> lines = exactCodeLines(block.getContent());
        // 围栏只扫描整段代码一次，不在每个候选行上重复扫描原代码。
        String fence = MarkdownTextNormalizer.codeFence(block);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String candidate = MarkdownTextNormalizer.renderCode(block, current + line, fence);
            if (!current.isEmpty() && !fits(candidate, wrappers, budget)) {
                appendCodePiece(pieces, block, current.toString(), fence);
                current.setLength(0);
            }
            String singleLine = MarkdownTextNormalizer.renderCode(block, line, fence);
            if (!fits(singleLine, wrappers, budget)) {
                throw budgetFailure(block, "代码第 " + (index + 1) + " 行及围栏无法完整容纳", length(singleLine));
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            appendCodePiece(pieces, block, current.toString(), fence);
        }
        return pieces;
    }

    private void appendCodePiece(List<String> pieces, DocumentBlock block, String body, String fence) {
        if (body.isBlank()) {
            throw budgetFailure(block, "代码空白段无法与有效代码合并", length(body));
        }
        pieces.add(MarkdownTextNormalizer.renderCode(block, body, fence));
    }

    private List<String> exactCodeLines(String code) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < code.length()) {
            int newline = code.indexOf('\n', start);
            if (newline < 0) {
                lines.add(code.substring(start));
                break;
            }
            lines.add(code.substring(start, newline + 1));
            start = newline + 1;
        }
        return lines;
    }

    private List<String> splitTable(DocumentBlock block, int budget, List<DocumentBlock> wrappers) {
        List<String> pieces = new ArrayList<>();
        List<String> current = new ArrayList<>();
        if (block.getRows().isEmpty()) {
            throw budgetFailure(block, "表头本身超过可用预算", length(block.getTableHeader()));
        }
        for (int index = 0; index < block.getRows().size(); index++) {
            String row = block.getRows().get(index);
            List<String> candidate = new ArrayList<>(current);
            candidate.add(row);
            if (!current.isEmpty()
                    && !fits(MarkdownTextNormalizer.renderTable(block, candidate), wrappers, budget)) {
                pieces.add(MarkdownTextNormalizer.renderTable(block, current));
                current.clear();
            }
            String singleRow = MarkdownTextNormalizer.renderTable(block, List.of(row));
            if (!fits(singleRow, wrappers, budget)) {
                throw budgetFailure(block, "表头及第 " + (index + 1) + " 个数据行无法完整容纳", length(singleRow));
            }
            current.add(row);
        }
        if (!current.isEmpty()) {
            pieces.add(MarkdownTextNormalizer.renderTable(block, current));
        }
        return pieces;
    }

    private List<String> packPieces(List<String> pieces, String separator, DocumentBlock container,
                                    List<DocumentBlock> wrappers, int budget) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            String candidate = piece;
            if (!current.isEmpty()) {
                candidate = current + separator + piece;
            }
            String rendered = wrapCurrent(container, candidate);
            if (!current.isEmpty() && !fits(rendered, wrappers, budget)) {
                result.add(wrapCurrent(container, current.toString()));
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(separator);
            }
            current.append(piece);
            if (!fits(wrapCurrent(container, current.toString()), wrappers, budget)) {
                throw new BlockBudgetException("结构片段加上外层标记后超出可用预算=" + budget);
            }
        }
        if (!current.isEmpty()) {
            result.add(wrapCurrent(container, current.toString()));
        }
        return result;
    }

    private String wrapCurrent(DocumentBlock container, String body) {
        if (container == null) {
            return body;
        }
        return MarkdownTextNormalizer.wrapContainer(container, body);
    }

    private boolean fits(String body, List<DocumentBlock> wrappers, int budget) {
        String rendered = body;
        for (int index = wrappers.size() - 1; index >= 0; index--) {
            rendered = MarkdownTextNormalizer.wrapContainer(wrappers.get(index), rendered);
        }
        return length(rendered) <= budget;
    }

    private List<DocumentChunk> assembleChunks(List<SectionChunk> drafts) {
        List<DocumentChunk> chunks = new ArrayList<>();
        List<SectionChunk> pending = new ArrayList<>();
        for (SectionChunk draft : drafts) {
            boolean merge = false;
            if (!pending.isEmpty() && sameParent(pending.getLast(), draft)
                    && length(renderGroup(pending)) < target) {
                List<SectionChunk> candidate = new ArrayList<>(pending);
                candidate.add(draft);
                merge = length(renderGroup(candidate)) <= maximum;
            }
            if (!pending.isEmpty() && !merge) {
                appendChunk(chunks, pending);
                pending.clear();
            }
            pending.add(draft);
        }
        if (!pending.isEmpty()) {
            appendChunk(chunks, pending);
        }
        return List.copyOf(chunks);
    }

    private boolean sameParent(SectionChunk previous, SectionChunk next) {
        if (!previous.isWholeSection() || !next.isWholeSection()) {
            return false;
        }
        List<DocumentBlock> left = previous.getOriginalHeadings();
        List<DocumentBlock> right = next.getOriginalHeadings();
        if (left.isEmpty() || left.size() != right.size()
                || left.getLast().getLevel() < 3 || right.getLast().getLevel() < 3) {
            return false;
        }
        for (int index = 0; index < left.size() - 1; index++) {
            if (left.get(index) != right.get(index)) {
                return false;
            }
        }
        return left.getLast() != right.getLast();
    }

    private String renderGroup(List<SectionChunk> group) {
        List<DocumentBlock> firstPath = group.getFirst().getHeadings();
        int common = firstPath.size();
        for (SectionChunk part : group) {
            common = Math.min(common, part.getHeadings().size());
            int index = 0;
            while (index < common && firstPath.get(index) == part.getHeadings().get(index)) {
                index++;
            }
            common = index;
        }
        StringBuilder result = new StringBuilder(renderHeadings(firstPath.subList(0, common)));
        for (SectionChunk part : group) {
            String ownHeadings = renderHeadings(part.getHeadings().subList(common, part.getHeadings().size()));
            if (!ownHeadings.isEmpty()) {
                appendSeparated(result, ownHeadings);
            }
            appendSeparated(result, part.getBody());
        }
        return result.toString();
    }

    private void appendSeparated(StringBuilder text, String content) {
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append(content);
    }

    private void appendChunk(List<DocumentChunk> chunks, List<SectionChunk> group) {
        for (SectionChunk part : group) {
            if (part.getBody().isBlank()) {
                throw new IllegalArgumentException("不能生成只有标题的 Chunk");
            }
        }
        String content = renderGroup(group);
        if (content.isBlank() || length(content) > maximum) {
            throw new IllegalArgumentException("最终 Chunk 为空或超过 maxCharacters=" + maximum);
        }
        chunks.add(DocumentChunk.builder()
                .index(chunks.size())
                .content(content)
                .build());
    }

    private String renderHeadings(List<DocumentBlock> headings) {
        StringBuilder result = new StringBuilder();
        for (DocumentBlock heading : headings) {
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(heading.getTitle());
        }
        return result.toString();
    }

    private int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private boolean isContainer(DocumentBlockType type) {
        return type == DocumentBlockType.LIST || type == DocumentBlockType.LIST_ITEM
                || type == DocumentBlockType.QUOTE;
    }

    private BlockBudgetException budgetFailure(DocumentBlock block, String reason, int characters) {
        return new BlockBudgetException(reason + "，Block=" + block.getIndex() + "，type=" + block.getType()
                + "，characters=" + characters + "，maxCharacters=" + maximum);
    }

    @Getter
    @Builder
    private static class Section {
        private final List<DocumentBlock> headings;
        private final List<DocumentBlock> blocks;
    }

    @Getter
    @Builder
    private static class SectionChunk {
        private final List<DocumentBlock> headings;
        private final List<DocumentBlock> originalHeadings;
        private final String body;
        private final boolean wholeSection;
    }

    /** 仅表示字符预算不足，允许上层尝试减少祖先标题；其他错误不参与降级。 */
    private static class BlockBudgetException extends IllegalArgumentException {
        private BlockBudgetException(String message) {
            super(message);
        }
    }

    /** 历史全文字符切分实现，不再调用。内层注释结束符写作 * / 以保留原代码。


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
    */
}
