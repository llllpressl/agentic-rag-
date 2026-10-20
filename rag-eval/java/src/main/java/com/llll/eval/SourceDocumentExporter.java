package com.llll.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 只读提取指定目录的原始文档，供 Ragas 获得完整上下文；不重新入库或切分。 */
@RequiredArgsConstructor
public class SourceDocumentExporter {

    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    public void export() throws Exception {
        Path root = Path.of(environment.getRequiredProperty("eval.source-directory")).toRealPath();
        Path output = Path.of(environment.getRequiredProperty("eval.output"));
        Files.createDirectories(output);
        List<Path> files = new ArrayList<>();
        collectFiles(root, files);
        files.sort(Comparator.comparing(Path::toString));
        Tika tika = new Tika();
        tika.setMaxStringLength(2_000_000);
        List<String> lines = new ArrayList<>();
        int parsed = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (!lowerName.endsWith(".md") && !lowerName.endsWith(".pdf")
                    && !lowerName.endsWith(".doc") && !lowerName.endsWith(".docx")) {
                continue;
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("relative_path", root.relativize(file).toString());
            document.put("doc_name", name);
            document.put("file_size", Files.size(file));
            byte[] bytes = Files.readAllBytes(file);
            document.put("file_sha256", java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
            try {
                String content = readDocument(file, bytes, lowerName, tika);
                document.put("content", content);
                document.put("parse_status", content.isBlank() ? "EMPTY" : "SUCCESS");
                document.put("parser", lowerName.endsWith(".md") ? "UTF8"
                        : (lowerName.endsWith(".pdf") ? "PDFBox" : (lowerName.endsWith(".doc") ? "POI-HWPF" : "Tika")));
                if (!content.isBlank()) {
                    parsed++;
                }
            } catch (Exception exception) {
                document.put("parse_status", "FAILED");
                document.put("error_type", exception.getClass().getSimpleName());
                document.put("content", "");
            }
            lines.add(mapper.writeValueAsString(document));
        }
        Files.write(output.resolve("source_documents.jsonl"), lines, StandardCharsets.UTF_8);
        System.out.println("SOURCES_COMPLETED files=" + lines.size() + ", parsed=" + parsed);
    }

    private void collectFiles(Path directory, List<Path> files) throws Exception {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    collectFiles(child, files);
                } else if (Files.isRegularFile(child)) {
                    files.add(child);
                }
            }
        }
    }

    /** 使用项目已有解析依赖；显式读取 PDF 和旧 Word，避免自动格式检测失败。 */
    private String readDocument(Path file, byte[] bytes, String lowerName, Tika tika) throws Exception {
        if (lowerName.endsWith(".md")) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (lowerName.endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(document);
            }
        }
        if (lowerName.endsWith(".doc")) {
            try (java.io.InputStream input = new java.io.ByteArrayInputStream(bytes);
                 HWPFDocument document = new HWPFDocument(input);
                 WordExtractor extractor = new WordExtractor(document)) {
                return extractor.getText();
            }
        }
        return tika.parseToString(file.toFile());
    }
}
