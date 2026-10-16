package com.llll.rag.knowledge.mq;

import com.llll.rag.ingestion.chunker.Chunker;
import com.llll.rag.ingestion.embedding.embeddingService.EmbeddingService;
import com.llll.rag.ingestion.model.DocumentChunk;
import com.llll.rag.ingestion.model.ParseResult;
import com.llll.rag.ingestion.parser.DocumentParser;
import com.llll.rag.ingestion.parser.DocumentParserRouter;
import com.llll.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import com.llll.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeDocMapper;
import com.llll.rag.knowledge.enums.MqTopicEnum;
import com.llll.rag.knowledge.mq.event.DocChunkEvent;
import com.llll.rag.knowledge.service.KnowledgeChunkService;
import com.llll.rag.knowledge.storage.RustFsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopicEnum.DOC_CHUNK_TOPIC,
        consumerGroup = "knowledge-document-chunk-group",
        selectorExpression = MqTopicEnum.DOC_CHUNK_TAG
)
public class DocChunkConsumer implements RocketMQListener<DocChunkEvent> {

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final RustFsStorageService rustFsStorageService;
    private final DocumentParserRouter parserRouter;
    private final Chunker chunker;
    private final EmbeddingService embeddingService;
    private final KnowledgeChunkService knowledgeChunkService;

    /**
    @Override
    public void onMessage(DocChunkEvent message) {
        String docId = message.getDocId();
        String kbId = message.getKbId();
        String operator = message.getUser();
        String collection = knowledgeBaseMapper.selectById(kbId).getCollection(); // 回填 chunk 字段的重要内容
        /// TODO 以后添加记录 parser， chunker， embedding， total 用时的 DocChunkTaskLogDo
        /// 1. 进行parser，这步之后应该大概就是 List<Block> 的样子
        /// 2. 进行chunker，这步之后应该大概就是 List<Chunk> 的样子
        /// 3. 进行embedding，这步之后应该大概就是 List<Embedding> 的样子
        /// 4. 进行入库操作，create，updateTime字段不用记录，enabled 默认 1，deleted 默认 0
        ///    create，updateBy 是operator，token count用预估的（在chunker 的count里的TokenCounter），
        ///    charCount 我不知道用啥字段存，我也不知道哪里获得
        ///    embedding 就是向量化之后的浮点数组，content 填入合并了block后的chunk内容，chunkIndex在入库时应该就会递增写入
        ///    id字段不用填，mybatis-plus自己填充，kbId，docId，collection都在上面，直接填入就行。
        /// 这个操作就不加Transactional注解了，考虑在 PG 入库的时候加上编程式事务，并在该事务成功后发异步消息去做es的同步入库或者异步入库。

    }
    */

    @Override
    public void onMessage(DocChunkEvent message) {
        if (message == null || !StringUtils.hasText(message.getDocId())
                || !StringUtils.hasText(message.getKbId()) || !StringUtils.hasText(message.getUser())) {
            throw new IllegalArgumentException("分块消息的文档、知识库和操作者不能为空");
        }
        KnowledgeDocDO document = knowledgeDocMapper.selectById(message.getDocId());
        if (document == null || !message.getKbId().equals(document.getKbId())) {
            throw new IllegalArgumentException("分块消息与文档所属知识库不匹配");
        }
        if (!"running".equals(document.getStatus()) || !Integer.valueOf(1).equals(document.getEnabled())) {
            throw new IllegalStateException("文档必须已启用且处于分块处理中");
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(document.getKbId());
        if (knowledgeBase == null || !StringUtils.hasText(knowledgeBase.getCollection())) {
            throw new IllegalArgumentException("文档所属知识库或 collection 不存在");
        }

        DocumentParser parser = parserRouter.require(document.getFileType());
        File sourceFile = null;
        try {
            // 1. RustFS 返回输入流，Parser 需要本地 File，因此先下载到临时文件。
            sourceFile = downloadToTemp(document.getFileUrl());
            ParseResult parseResult = parser.parse(sourceFile);

            // 2. 复用现有 Chunker，content 同时作为向量化输入和入库正文。
            List<DocumentChunk> chunks = chunker.chunk(parseResult.getBlocks());
            List<String> texts = new ArrayList<>();
            for (DocumentChunk chunk : chunks) {
                texts.add(chunk.getContent());
            }

            // 3. 全部向量化完成后才进入 PG 保存事务。
            List<float[]> vectors = embeddingService.embedBatch(texts);
            knowledgeChunkService.saveChunks(message.getDocId(), chunks, vectors, message.getUser());
        } finally {
            if (sourceFile != null) {
                deleteTempFile(sourceFile.toPath());
            }
        }
    }

    private File downloadToTemp(String objectKey) {
        String extension = StringUtils.getFilenameExtension(objectKey);
        if (extension == null || !extension.matches("[A-Za-z0-9]{1,16}")) {
            throw new IllegalArgumentException("原始文件 object key 缺少合法扩展名");
        }
        Path temporaryPath = null;
        boolean downloadCompleted = false;
        try {
            temporaryPath = Files.createTempFile("knowledge-doc-", "." + extension.toLowerCase(Locale.ROOT));
            try (ResponseInputStream<GetObjectResponse> stream = rustFsStorageService.download(objectKey)) {
                Files.copy(stream, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
            }
            File file = temporaryPath.toFile();
            downloadCompleted = true;
            return file;
        } catch (IOException exception) {
            throw new IllegalStateException("下载原始文档到临时文件失败", exception);
        } finally {
            if (!downloadCompleted && temporaryPath != null) {
                deleteTempFile(temporaryPath);
            }
        }
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            // 清理失败只记录日志，避免掩盖处理异常或已提交的 PG 保存结果。
            log.warn("清理分块临时文件失败：{}", path, exception);
        }
    }
}
