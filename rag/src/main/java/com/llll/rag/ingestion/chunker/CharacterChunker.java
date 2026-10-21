package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.chunker.properties.ChunkingProperties;
import com.llll.rag.ingestion.model.DocumentBlock;
import com.llll.rag.ingestion.model.DocumentBlockType;
import com.llll.rag.ingestion.model.DocumentChunk;
import com.llll.rag.ingestion.parser.MarkdownTextNormalizer;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 复用 AST 章节及结构边界，普通正文按分隔符优先级递归切分后合并。 */
@Slf4j
@Component
public class CharacterChunker implements Chunker {
    private final int target;
    private final int maximum;
    private final RecursiveTextSplitter textSplitter = new RecursiveTextSplitter();
    private final HtmlTableSplitter htmlTableSplitter = new HtmlTableSplitter();

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
        int boundaryGroup = 0;
        for (DocumentBlock block : blocks) {
            validateBlock(block);
            if (block.getType() == DocumentBlockType.THEMATIC_BREAK) {
                if (!body.isEmpty()) {
                    sections.add(Section.builder()
                            .headings(List.copyOf(headings))
                            .blocks(List.copyOf(body))
                            .boundaryGroup(boundaryGroup)
                            .build());
                    body.clear();
                }
                boundaryGroup++;
                continue;
            }
            if (block.getType() == DocumentBlockType.HEADING) {
                if (!body.isEmpty()) {
                    sections.add(Section.builder()
                            .headings(List.copyOf(headings))
                            .blocks(List.copyOf(body))
                            .boundaryGroup(boundaryGroup)
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
                    .boundaryGroup(boundaryGroup)
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
                            .boundaryGroup(section.getBoundaryGroup())
                            .build());
                }
                return result;
            } catch (ChunkBudgetException exception) {
                if (selectedHeadings.size() > 1) {
                    // 优先移除最远祖先；最具体标题仍过长时才显式缩略并记录日志。
                    selectedHeadings.removeFirst();
                } else if (!selectedHeadings.isEmpty()
                        && length(selectedHeadings.getFirst().getTitle()) > 1) {
                    DocumentBlock heading = selectedHeadings.getFirst();
                    int retained = Math.max(1, Math.min(length(heading.getTitle()) / 2, maximum / 4));
                    String shortened;
                    if (retained == 1) {
                        shortened = heading.getTitle().substring(0, heading.getTitle().offsetByCodePoints(0, 1));
                    } else {
                        shortened = heading.getTitle().substring(0,
                                heading.getTitle().offsetByCodePoints(0, retained - 1)) + "…";
                    }
                    selectedHeadings.set(0, DocumentBlock.builder()
                            .index(heading.getIndex())
                            .type(DocumentBlockType.HEADING)
                            .level(heading.getLevel())
                            .title(shortened)
                            .content("")
                            .build());
                    log.warn("标题上下文超出分块预算，保留最具体标题的前缀，Block={}，保留码点={}",
                            heading.getIndex(), retained);
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
        List<List<String>> groups = new ArrayList<>();
        StringBuilder paragraphs = new StringBuilder();
        for (DocumentBlock block : blocks) {
            if (block.getType() == DocumentBlockType.PARAGRAPH) {
                if (!paragraphs.isEmpty()) {
                    paragraphs.append("\n\n");
                }
                paragraphs.append(block.getContent());
            } else {
                appendParagraphs(groups, paragraphs, budget, preferred);
                groups.add(splitBlock(block, budget, preferred, List.of()));
            }
        }
        appendParagraphs(groups, paragraphs, budget, preferred);
        return packPieces(groups, "\n\n", null, List.of(), budget, preferred);
    }

    private void appendParagraphs(List<List<String>> groups, StringBuilder paragraphs, int budget, int preferred) {
        if (!paragraphs.isEmpty()) {
            // 递归结果自带原分隔符；这里只收集，不能在这些片段之间插入额外空行。
            groups.add(textSplitter.split(paragraphs.toString(), budget, preferred, List.of()));
            paragraphs.setLength(0);
        }
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
        if (type == DocumentBlockType.RAW) {
            return htmlTableSplitter.split(block, budget, wrappers);
        }
        if (isContainer(type)) {
            List<DocumentBlock> childWrappers = new ArrayList<>(wrappers);
            if (type != DocumentBlockType.LIST) {
                childWrappers.add(block);
            }
            List<List<String>> pieces = new ArrayList<>();
            for (DocumentBlock child : block.getChildren()) {
                pieces.add(splitBlock(child, budget, preferred, childWrappers));
            }
            String separator = "\n\n";
            if (type == DocumentBlockType.LIST) {
                separator = "\n";
            }
            return packPieces(pieces, separator, block, wrappers, budget, preferred);
        }
        throw budgetFailure(block, "原始 HTML 或未识别结构不能安全拆分", length(complete));
    }



    private List<String> splitParagraph(DocumentBlock block, int budget, int preferred,
                                        List<DocumentBlock> wrappers) {
        return textSplitter.split(block.getContent(), budget, preferred, wrappers);
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
                /** 原行为：单行超长直接失败；现仅对这一行按 Unicode 码点安全拆分。 */
                pieces.addAll(splitLongCodeLine(block, line, fence, budget, wrappers));
                continue;
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            appendCodePiece(pieces, block, current.toString(), fence);
        }
        return pieces;
    }

    private List<String> splitLongCodeLine(DocumentBlock block, String line, String fence,
                                            int budget, List<DocumentBlock> wrappers) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int lower = 0;
            int upper = Math.min(budget, line.codePointCount(start, line.length()));
            while (lower < upper) {
                int middle = lower + (upper - lower + 1) / 2;
                int end = line.offsetByCodePoints(start, middle);
                String rendered = MarkdownTextNormalizer.renderCode(block, line.substring(start, end), fence);
                if (fits(rendered, wrappers, budget)) {
                    lower = middle;
                } else {
                    upper = middle - 1;
                }
            }
            if (lower == 0) {
                throw budgetFailure(block, "代码围栏及语言信息后没有正文预算", budget);
            }
            int end = line.offsetByCodePoints(start, lower);
            appendCodePiece(pieces, block, line.substring(start, end), fence);
            start = end;
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

    /** 原合并只受硬上限约束；现在将目标大小作为软约束，不拆开完整结构来凑满。 */
    private List<String> packPieces(List<List<String>> groups, String separator, DocumentBlock container,
                                    List<DocumentBlock> wrappers, int budget, int preferred) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (List<String> pieces : groups) {
            for (int pieceIndex = 0; pieceIndex < pieces.size(); pieceIndex++) {
                String piece = pieces.get(pieceIndex);
                String candidate = piece;
                if (!current.isEmpty()) {
                    candidate = current + separator + piece;
                }
                String rendered = wrapCurrent(container, candidate);
                int currentLength = RecursiveTextSplitter.measuredLength(
                        wrapCurrent(container, current.toString()), wrappers);
                int candidateLength = RecursiveTextSplitter.measuredLength(rendered, wrappers);
                boolean nearerTarget = candidateLength > preferred
                        && Math.abs(currentLength - preferred) <= Math.abs(candidateLength - preferred);
                if (!current.isEmpty() && (pieceIndex > 0 || !fits(rendered, wrappers, budget) || nearerTarget)) {
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
        if (previous.getBoundaryGroup() != next.getBoundaryGroup()) {
            return false;
        }
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
        private final int boundaryGroup;
    }

    @Getter
    @Builder
    private static class SectionChunk {
        private final List<DocumentBlock> headings;
        private final List<DocumentBlock> originalHeadings;
        private final String body;
        private final boolean wholeSection;
        private final int boundaryGroup;
    }

    /** 仅表示字符预算不足，允许上层尝试减少祖先标题；其他错误不参与降级。 */
    private static class BlockBudgetException extends ChunkBudgetException {
        private BlockBudgetException(String message) {
            super(message);
        }
    }

}
