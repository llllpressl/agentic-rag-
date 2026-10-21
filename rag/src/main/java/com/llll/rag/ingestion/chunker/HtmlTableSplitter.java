package com.llll.rag.ingestion.chunker;

import com.llll.rag.ingestion.model.DocumentBlock;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/** 超长 HTML 表格只按完整行组拆分；跨行单元格覆盖的行始终属于同一组。 */
@Slf4j
final class HtmlTableSplitter {

    List<String> split(DocumentBlock block, int budget, List<DocumentBlock> wrappers) {
        try {
            return splitTable(block, budget, wrappers);
        } catch (IllegalArgumentException exception) {
            log.warn("HTML 结构无法安全分块，Block={}，原因={}", block.getIndex(), exception.getMessage());
            throw exception;
        }
    }

    private List<String> splitTable(DocumentBlock block, int budget, List<DocumentBlock> wrappers) {
        Parser parser = Parser.htmlParser().setTrackErrors(32);
        Element root = new Element("body");
        List<Node> nodes = parser.parseFragmentInput(block.getContent(), root, "");
        for (Node node : nodes) {
            root.appendChild(node);
        }
        List<Element> tables = structuralChildren(root, "table");
        if (!parser.getErrors().isEmpty() || tables.size() != 1) {
            throw structureFailure(block, "只支持完整且独立的单张 HTML 表格；不修复畸形标签或丢弃表外内容");
        }
        Element table = tables.getFirst();
        // 关闭格式化，避免序列化时向单元格正文额外插入缩进和换行。
        Document document = Document.createShell("");
        document.outputSettings().prettyPrint(false);
        document.body().appendChild(table);
        List<Element> groups = structuralChildren(table, "thead", "tbody", "tfoot");
        Element header = null;
        Element implicitHeaderGroup = null;
        List<Element> implicitHeaderRows = new ArrayList<>();
        int firstDataRow = 0;
        if (!groups.isEmpty() && groups.getFirst().normalName().equals("thead")) {
            header = groups.getFirst();
            validateRowSpans(structuralChildren(header, "tr"), block);
        } else if (!groups.isEmpty()) {
            List<Element> rows = structuralChildren(groups.getFirst(), "tr");
            while (firstDataRow < rows.size() && isHeaderRow(rows.get(firstDataRow))) {
                implicitHeaderRows.add(rows.get(firstDataRow));
                firstDataRow++;
            }
            if (!implicitHeaderRows.isEmpty()) {
                for (int index = 0; index < implicitHeaderRows.size(); index++) {
                    for (Element cell : implicitHeaderRows.get(index).children()) {
                        int rowspan = span(cell, "rowspan", rows.size() - index, block);
                        if (rowspan > implicitHeaderRows.size() - index) {
                            throw structureFailure(block, "隐式 th 表头的 rowspan 跨越了数据行，不能单独重复");
                        }
                    }
                }
                validateRowSpans(implicitHeaderRows, block);
                implicitHeaderGroup = groups.getFirst();
                header = implicitHeaderGroup.shallowClone();
                for (Element row : implicitHeaderRows) {
                    header.appendChild(row.clone());
                }
            }
        }
        if (header == null) {
            throw structureFailure(block, "超长表格缺少明确 thead 或起始 th 表头，不能猜测并重复数据行");
        }
        List<String> result = new ArrayList<>();
        for (Element group : groups) {
            if (group == header) {
                continue;
            }
            if (group.normalName().equals("thead")) {
                throw structureFailure(block, "表头必须位于表格开头且只出现一次");
            }
            List<Element> rows = structuralChildren(group, "tr");
            if (group == implicitHeaderGroup) {
                rows = rows.subList(firstDataRow, rows.size());
            }
            validateRowSpans(rows, block);
            List<Element> pending = new ArrayList<>();
            int start = 0;
            while (start < rows.size()) {
                int end = rowGroupEnd(rows, start, block);
                List<Element> unit = rows.subList(start, end);
                List<Element> candidate = new ArrayList<>(pending);
                candidate.addAll(unit);
                if (!pending.isEmpty() && !fits(render(table, header, group, candidate), budget, wrappers)) {
                    result.add(render(table, header, group, pending));
                    pending.clear();
                }
                String singleGroup = render(table, header, group, unit);
                if (!fits(singleGroup, budget, wrappers)) {
                    throw new ChunkBudgetException("HTML 表头及不可拆分行组超过预算，Block=" + block.getIndex()
                            + "，行=" + (start + 1) + ".." + end + "，budget=" + budget);
                }
                pending.addAll(unit);
                start = end;
            }
            if (!pending.isEmpty()) {
                result.add(render(table, header, group, pending));
            }
        }
        if (result.isEmpty()) {
            throw new ChunkBudgetException("HTML 表格只有表头且整体超过预算，Block=" + block.getIndex());
        }
        return result;
    }

    private List<Element> structuralChildren(Element parent, String... allowed) {
        List<Element> result = new ArrayList<>();
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode && ((TextNode) child).isBlank()) {
                continue;
            }
            boolean accepted = false;
            if (child instanceof Element) {
                Element element = (Element) child;
                for (String name : allowed) {
                    if (element.normalName().equals(name)) {
                        accepted = true;
                        break;
                    }
                }
                if (accepted) {
                    result.add(element);
                }
            }
            if (!accepted) {
                throw new IllegalArgumentException("HTML " + parent.normalName()
                        + " 内存在暂不支持安全拆分的节点：" + child.nodeName());
            }
        }
        return result;
    }

    private boolean isHeaderRow(Element row) {
        List<Element> cells = structuralChildren(row, "th", "td");
        if (cells.isEmpty()) {
            return false;
        }
        for (Element cell : cells) {
            if (!cell.normalName().equals("th")) {
                return false;
            }
        }
        return true;
    }

    private void validateRowSpans(List<Element> rows, DocumentBlock block) {
        for (int index = 0; index < rows.size(); index++) {
            List<Element> cells = structuralChildren(rows.get(index), "th", "td");
            if (cells.isEmpty()) {
                throw structureFailure(block, "表格包含空行，无法确定单元格对应关系");
            }
            for (Element cell : cells) {
                if (!cell.select("table").isEmpty()) {
                    throw structureFailure(block, "超长嵌套表格暂不支持安全拆分");
                }
                int rowspan = span(cell, "rowspan", rows.size() - index, block);
                span(cell, "colspan", 1, block);
                if (rowspan > rows.size() - index) {
                    throw structureFailure(block, "rowspan 超出了所属表头或行组");
                }
            }
        }
    }

    private int rowGroupEnd(List<Element> rows, int start, DocumentBlock block) {
        int end = start + 1;
        for (int index = start; index < end; index++) {
            for (Element cell : rows.get(index).children()) {
                int rowspan = span(cell, "rowspan", rows.size() - index, block);
                end = Math.max(end, index + rowspan);
            }
        }
        return end;
    }

    private int span(Element cell, String attribute, int remainingRows, DocumentBlock block) {
        if (!cell.hasAttr(attribute)) {
            return 1;
        }
        String value = cell.attr(attribute).strip();
        try {
            int span = Integer.parseInt(value);
            if (span == 0 && attribute.equals("rowspan")) {
                return remainingRows;
            }
            if (span < 1) {
                throw structureFailure(block, attribute + " 必须为合法正整数，rowspan 还允许 0");
            }
            return span;
        } catch (NumberFormatException exception) {
            throw structureFailure(block, attribute + " 不是合法整数");
        }
    }

    private String render(Element table, Element header, Element group, List<Element> rows) {
        Document document = Document.createShell("");
        document.outputSettings().prettyPrint(false);
        Element copy = table.shallowClone();
        document.body().appendChild(copy);
        copy.appendChild(header.clone());
        Element body = group.shallowClone();
        for (Element row : rows) {
            body.appendChild(row.clone());
        }
        copy.appendChild(body);
        return copy.outerHtml();
    }

    private boolean fits(String text, int budget, List<DocumentBlock> wrappers) {
        return RecursiveTextSplitter.measuredLength(text, wrappers) <= budget;
    }

    private IllegalArgumentException structureFailure(DocumentBlock block, String reason) {
        return new IllegalArgumentException("HTML 表格结构错误，Block=" + block.getIndex() + "；" + reason);
    }
}
