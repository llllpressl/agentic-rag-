package com.llll.rag.knowledge.service.serviceImpl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llll.rag.ingestion.chunker.count.TokenCounter;
import com.llll.rag.ingestion.model.DocumentChunk;
import com.llll.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeChunkEsDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import com.llll.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeDocMapper;
import com.llll.rag.knowledge.service.KnowledgeChunkService;
import com.llll.rag.knowledge.service.KnowledgeBaseOperationLock;
import com.llll.rag.knowledge.enums.MqTopicEnum;
import com.llll.rag.knowledge.mq.event.DocChunkEsEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.io.IOException;

@Slf4j
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
    private final ElasticsearchClient elasticsearchClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final KnowledgeBaseOperationLock knowledgeBaseOperationLock;

    @Value("${knowledge.elasticsearch.chunk-index:knowledge_chunk}")
    private String chunkIndex;

    @Value("${knowledge.elasticsearch.bulk-size:100}")
    private int esBulkSize;

    /**
    @Override
    public void saveChunks(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator) {
    */
    private void saveChunksWithLock(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator) {
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
                knowledgeChunkMapper.insert(batch); //TODO 这里插入失败应该会抛异常
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

        // 当前调用链没有外层事务，事务模板返回后 PG 已提交；success 只代表 PG 分块完成。
        // TODO 若以后加入外层事务，消息发送必须改为在整个事务提交后执行。
        String eventId = UUID.randomUUID().toString();
        try {
            KnowledgeDocDO savedDocument = knowledgeDocMapper.selectById(docId);
            if (savedDocument == null) {
                throw new IllegalStateException("PG 已提交，但发送 ES 消息时文档不存在");
            }
            DocChunkEsEvent event = DocChunkEsEvent.builder()
                    .eventId(eventId)
                    .docId(docId)
                    .kbId(savedDocument.getKbId())
                    .build();
            SendResult sendResult = rocketMQTemplate.syncSend(
                    MqTopicEnum.DOC_CHUNK_ES.getDestination(), event);
            if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("ES 同步消息未获得 SEND_OK，发送结果需要核对");
            }
            log.info("ES 同步消息发送成功，eventId={}，docId={}", eventId, docId);
        } catch (RuntimeException exception) {
            // PG 无法回滚；重新消费原始分块消息会被 success 状态拦住，不能靠重新分块补发。
            // TODO 增加持久化同步任务与定时补发；本次仅记录日志，未提供自动补偿能力。
            log.error("PG 已提交，ES 同步消息发送失败或结果不确定，需人工核对，eventId={}，docId={}",
                    eventId, docId, exception);
        }
    }

    /**
    @Override
    public void saveChunksInEs(DocChunkEsEvent event) {
    */
    private void saveChunksInEsWithLock(DocChunkEsEvent event) {
        // 1. 校验消息和文档归属；只同步 PG 分块已经完成的文档。
        if (event == null || !StringUtils.hasText(event.getEventId())
                || !StringUtils.hasText(event.getDocId()) || !StringUtils.hasText(event.getKbId())) {
            throw new IllegalArgumentException("ES 同步消息的标识、文档和知识库不能为空");
        }
        if (!StringUtils.hasText(chunkIndex) || esBulkSize < 1 || esBulkSize > 1000) {
            throw new IllegalStateException("ES 索引名称不能为空，Bulk 批次大小必须在 1 到 1000 之间");
        }
        KnowledgeDocDO document = knowledgeDocMapper.selectById(event.getDocId());
        if (document == null || !event.getKbId().equals(document.getKbId())) {
            throw new IllegalArgumentException("ES 同步消息与文档所属知识库不匹配");
        }
        if (!"success".equals(document.getStatus()) || !Integer.valueOf(1).equals(document.getEnabled())) {
            throw new IllegalStateException("ES 同步要求文档已启用且 PG 分块已经完成");
        }
        if (document.getChunkCount() == null || document.getChunkCount() < 1) {
            throw new IllegalStateException("文档没有已完成的分块");
        }
        if (knowledgeBaseMapper.selectById(event.getKbId()) == null) {
            throw new IllegalStateException("ES 同步消息所属知识库不存在");
        }

        // 2. 按分块序号分页读取，避免加载整篇文档与 embedding；逻辑删除过滤由 MyBatis-Plus 追加。
        int lastChunkIndex = -1;
        int indexedCount = 0;
        while (true) {
            LambdaQueryWrapper<KnowledgeChunkDO> query = new LambdaQueryWrapper<KnowledgeChunkDO>()
                    .select(
                            KnowledgeChunkDO::getId,
                            KnowledgeChunkDO::getKbId,
                            KnowledgeChunkDO::getDocId,
                            KnowledgeChunkDO::getChunkIndex,
                            KnowledgeChunkDO::getContent,
                            KnowledgeChunkDO::getEnabled,
                            KnowledgeChunkDO::getDeleted
                    )
                    .eq(KnowledgeChunkDO::getDocId, event.getDocId())
                    .eq(KnowledgeChunkDO::getKbId, event.getKbId())
                    .gt(KnowledgeChunkDO::getChunkIndex, lastChunkIndex)
                    .orderByAsc(KnowledgeChunkDO::getChunkIndex)
                    .last("LIMIT " + esBulkSize);
            List<KnowledgeChunkDO> chunks = knowledgeChunkMapper.selectList(query);
            if (chunks.isEmpty()) {
                break;
            }

            // 3. 直接在当前方法内转换 ES 正文；Chunk 主键放入 _id，不写入正文。
            BulkRequest.Builder requestBuilder = new BulkRequest.Builder();
            for (KnowledgeChunkDO chunk : chunks) {
                KnowledgeChunkEsDO esDocument = KnowledgeChunkEsDO.builder()
                        .kbId(chunk.getKbId())
                        .docId(chunk.getDocId())
                        .content(chunk.getContent())
                        .enabled(chunk.getEnabled())
                        .deleted(chunk.getDeleted())
                        .build();
                // index 操作对同一个 _id 创建或覆盖，允许同一批 Chunk 重复消费。
                requestBuilder.operations(operation -> operation
                        .index(index -> index
                                .index(chunkIndex)
                                .requireAlias(true)
                                .id(chunk.getId())
                                .document(esDocument)
                        )
                );
            }

            // 4. 同步等待本批 Bulk 结果，再正常返回给 MQ；HTTP 成功仍需要检查每项错误。
            BulkResponse response;
            try {
                response = elasticsearchClient.bulk(requestBuilder.build());
            } catch (IOException exception) {
                throw new IllegalStateException("ES Bulk 请求失败，eventId=" + event.getEventId(), exception);
            }
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("ES Chunk 写入失败，eventId={}，docId={}，chunkId={}，status={}，reason={}",
                                event.getEventId(), event.getDocId(), item.id(), item.status(), item.error().reason());
                    }
                }
                throw new IllegalStateException("ES Bulk 存在失败条目，eventId=" + event.getEventId());
            }

            indexedCount += chunks.size();
            lastChunkIndex = chunks.get(chunks.size() - 1).getChunkIndex();
        }

        // 5. 对照 PG 文档记录核对本次处理数量；已成功的批次在重试时按相同 _id 覆盖。
        if (indexedCount != document.getChunkCount()) {
            throw new IllegalStateException("ES 同步的 Chunk 数量与 PG 文档记录不一致，eventId=" + event.getEventId());
        }
        // TODO 删除、禁用、重新分块需追加清理与批次版本隔离，当前仅处理首次分块的索引写入。
        // Bulk 成功表示写入被确认，搜索可见性仍取决于 ES refresh；消费重试耗尽后需处理死信。
        log.info("ES Chunk 同步完成，eventId={}，docId={}，chunkCount={}",
                event.getEventId(), event.getDocId(), indexedCount);
    }

    /** 在重新校验和实际 PG 写入期间持有 KB 互斥，模型调用不占用互斥。 */
    @Override
    public void saveChunks(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator) {
        validateInput(docId, chunks, vectors, operator);
        KnowledgeDocDO document = knowledgeDocMapper.selectById(docId);
        if (document == null) {
            log.info("文档已删除，忽略旧分块结果，docId={}", docId);
            return;
        }
        knowledgeBaseOperationLock.execute(document.getKbId(), () -> {
            if (knowledgeBaseMapper.selectById(document.getKbId()) == null
                    || knowledgeDocMapper.selectById(docId) == null) {
                log.info("知识库或文档已删除，忽略旧分块结果，docId={}", docId);
                return null;
            }
            saveChunksWithLock(docId, chunks, vectors, operator);
            return null;
        });
    }

    /** 互斥覆盖所有 Bulk 请求，删除须等待已开始的同步退出。 */
    @Override
    public void saveChunksInEs(DocChunkEsEvent event) {
        if (event == null || !StringUtils.hasText(event.getEventId())
                || !StringUtils.hasText(event.getDocId()) || !StringUtils.hasText(event.getKbId())) {
            throw new IllegalArgumentException("ES 同步消息的标识、文档和知识库不能为空");
        }
        knowledgeBaseOperationLock.execute(event.getKbId(), () -> {
            if (knowledgeBaseMapper.selectById(event.getKbId()) == null
                    || knowledgeDocMapper.selectById(event.getDocId()) == null) {
                log.info("知识库或文档已删除，忽略旧 ES 同步，eventId={}", event.getEventId());
                return null;
            }
            saveChunksInEsWithLock(event);
            return null;
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
