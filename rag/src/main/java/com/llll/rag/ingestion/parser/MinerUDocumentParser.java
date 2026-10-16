package com.llll.rag.ingestion.parser;

import com.llll.rag.ingestion.model.ParseResult;
import com.llll.rag.ingestion.parser.mineru.MinerUClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@RequiredArgsConstructor
public class MinerUDocumentParser implements DocumentParser {

    private final MinerUClient minerUClient;
    private final MarkdownDocumentParser markdownParser;

    @Override
    public boolean supports(String fileType) {
        return "pdf".equals(fileType) || "doc".equals(fileType) || "docx".equals(fileType);
    }

    @Override
    public ParseResult parse(File file) {
        return markdownParser.parseMarkdown(minerUClient.extractMarkdown(file));
    }
}
