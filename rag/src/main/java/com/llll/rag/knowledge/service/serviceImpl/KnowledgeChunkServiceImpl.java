package com.llll.rag.knowledge.service.serviceImpl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llll.rag.ingestion.chunker.count.TokenCounter;
import com.llll.rag.ingestion.model.DocumentChunk;
import com.llll.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import com.llll.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeDocMapper;
import com.llll.rag.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private static final int INSERT_BATCH_SIZE = 100;
    private static final int EMBEDDING_DIMENSIONS = 1024;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final TokenCounter tokenCounter;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void saveChunks(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator) {
        validateInput(docId, chunks, vectors, operator);

        // 模型调用已经完成；事务仅包含关联信息读取、分块插入和文档完成更新。
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDocDO document = knowledgeDocMapper.selectById(docId);
            if (document == null) {
                throw new IllegalArgumentException("待保存分块的文档不存在");
            }
            if (!"running".equals(document.getStatus())) {
                throw new IllegalStateException("文档未处于分块处理中");
            }
            KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(document.getKbId());
            if (knowledgeBase == null || !StringUtils.hasText(knowledgeBase.getCollection())) {
                throw new IllegalArgumentException("文档所属知识库或 collection 不存在");
            }

            Date now = new Date();
            List<KnowledgeChunkDO> chunkEntities = buildChunkDOs(
                    document, knowledgeBase.getCollection(), chunks, vectors, operator, now);
            for (int start = 0; start < chunkEntities.size(); start += INSERT_BATCH_SIZE) {
                int end = Math.min(start + INSERT_BATCH_SIZE, chunkEntities.size());
                List<KnowledgeChunkDO> batch = chunkEntities.subList(start, end);
                int inserted = knowledgeChunkMapper.insertBatch(batch);
                if (inserted != batch.size()) {
                    throw new IllegalStateException("分块保存数量与预期不一致");
                }
            }

            // 直接调用 BaseMapper 已有方法，不修改 KnowledgeDocMapper。
            LambdaUpdateWrapper<KnowledgeDocDO> update = new LambdaUpdateWrapper<KnowledgeDocDO>()
                    .eq(KnowledgeDocDO::getId, docId)
                    .set(KnowledgeDocDO::getChunkCount, chunkEntities.size())
                    .set(KnowledgeDocDO::getStatus, "success")
                    .set(KnowledgeDocDO::getUpdateBy, operator)
                    .set(KnowledgeDocDO::getUpdateTime, now);
            if (knowledgeDocMapper.update(null, update) != 1) {
                throw new IllegalStateException("更新文档分块结果失败");
            }
        });
    }

    private List<KnowledgeChunkDO> buildChunkDOs(KnowledgeDocDO document, String collection,
                                                List<DocumentChunk> chunks, List<float[]> vectors,
                                                String operator, Date now) {
        List<KnowledgeChunkDO> entities = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            String content = chunk.getContent();
            KnowledgeChunkDO entity = KnowledgeChunkDO.builder()
                    .kbId(document.getKbId())
                    .collection(collection)
                    .docId(document.getId())
                    .chunkIndex(chunk.getIndex())
                    .content(content)
                    .embedding(vectors.get(index))
                    .charCount(content.codePointCount(0, content.length()))
                    .tokenCount(tokenCounter.count(content))
                    .createTime(now)
                    .updateTime(now)
                    .createBy(operator)
                    .updateBy(operator)
                    .enabled(1)
                    .deleted(0)
                    .build();
            // id 保持为空，由 MyBatis-Plus 的 ASSIGN_ID 在执行插入时填充。
            entities.add(entity);
        }
        return entities;
    }

    private void validateInput(String docId, List<DocumentChunk> chunks, List<float[]> vectors,
                               String operator) {
        if (!StringUtils.hasText(docId) || !StringUtils.hasText(operator)) {
            throw new IllegalArgumentException("docId 和分块操作者不能为空");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("待保存分块不能为空");
        }
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new IllegalArgumentException("向量数量必须与分块数量一致");
        }
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            if (chunk == null || !Integer.valueOf(index).equals(chunk.getIndex())) {
                throw new IllegalArgumentException("分块序号必须从 0 开始且连续");
            }
            if (!StringUtils.hasText(chunk.getContent())) {
                throw new IllegalArgumentException("分块正文不能为空");
            }
            float[] vector = vectors.get(index);
            if (vector == null || vector.length != EMBEDDING_DIMENSIONS) {
                throw new IllegalArgumentException("分块向量必须为 1024 维");
            }
            boolean hasNonZeroValue = false;
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("分块向量包含非有限数值");
                }
                if (value != 0.0f) {
                    hasNonZeroValue = true;
                }
            }
            if (!hasNonZeroValue) {
                throw new IllegalArgumentException("分块向量不能为零向量");
            }
        }
    }
}
