package com.llll.rag.knowledge.service.serviceImpl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llll.framwork.context.UserContext;
import com.llll.rag.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeChunkPageVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeChunkVO;
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
/** 原依赖：import com.llll.rag.knowledge.service.KnowledgeBaseOperationLock; */
/** 原依赖：import com.llll.rag.knowledge.enums.MqTopicEnum; */
/** 原依赖：import com.llll.rag.knowledge.mq.event.DocChunkEsEvent; */
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
/** 原依赖：import org.apache.rocketmq.client.producer.SendResult; */
/** 原依赖：import org.apache.rocketmq.client.producer.SendStatus; */
/** 原依赖：import org.apache.rocketmq.spring.core.RocketMQTemplate; */
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
/** 原依赖：import java.util.UUID; */
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

    @Value("${knowledge.elasticsearch.chunk-index:knowledge_chunk}")
    private String chunkIndex;

    @Value("${knowledge.elasticsearch.bulk-size:100}")
    private int esBulkSize;

    @Override
    public KnowledgeChunkVO detail(String docId, String chunkId) {
        KnowledgeDocDO document = requireChunkDocument(docId);
        if (!StringUtils.hasText(chunkId)) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        KnowledgeChunkDO chunk = knowledgeChunkMapper.selectOne(
                buildChunkReadQuery(document)
                        .eq(KnowledgeChunkDO::getId, chunkId));
        if (chunk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该文档下的分块不存在或已删除");
        }
        return toChunkVO(chunk);
    }

    @Override
    public KnowledgeChunkPageVO page(String docId, KnowledgeChunkPageRequest request) {
        KnowledgeDocDO document = requireChunkDocument(docId);
        if (request == null || request.getPageNo() == null || request.getPageSize() == null
                || request.getPageNo() < 1 || request.getPageSize() < 1 || request.getPageSize() > 100) {
            throw new IllegalArgumentException("页码须大于等于 1，每页数量须在 1 到 100 之间");
        }

        // 总数与列表都限定同一文档和知识库，逻辑删除过滤由 MyBatis-Plus 追加。
        LambdaQueryWrapper<KnowledgeChunkDO> countQuery = new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, document.getId())
                .eq(KnowledgeChunkDO::getKbId, document.getKbId());
        Long total = knowledgeChunkMapper.selectCount(countQuery);
        long offset = (long) (request.getPageNo() - 1) * request.getPageSize();
        List<KnowledgeChunkVO> records = new ArrayList<>();
        if (offset < total) {
            List<KnowledgeChunkDO> chunks = knowledgeChunkMapper.selectList(
                    buildChunkReadQuery(document)
                            .orderByAsc(KnowledgeChunkDO::getChunkIndex)
                            .orderByAsc(KnowledgeChunkDO::getId)
                            // 沿用文档分页写法，仅拼接已经校验的分页数字。
                            .last("LIMIT " + request.getPageSize() + " OFFSET " + offset));
            for (KnowledgeChunkDO chunk : chunks) {
                records.add(toChunkVO(chunk));
            }
        }
        return KnowledgeChunkPageVO.builder()
                .records(records)
                .total(total)
                .page(request.getPageNo())
                .size(request.getPageSize())
                .build();
    }

    /** 管理查询允许查看已提交到 PG 的分块，不受检索的 success 状态限制。 */
    private KnowledgeDocDO requireChunkDocument(String docId) {
        if (UserContext.getUser() == null || !StringUtils.hasText(UserContext.getUser().getUsername())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        KnowledgeDocDO document = knowledgeDocMapper.selectById(docId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或已删除");
        }
        if (knowledgeBaseMapper.selectById(document.getKbId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档所属知识库不存在或已删除");
        }
        return document;
    }

    /** 只读取页面展示字段，详情和分页均不从数据库加载 embedding。 */
    private LambdaQueryWrapper<KnowledgeChunkDO> buildChunkReadQuery(KnowledgeDocDO document) {
        return new LambdaQueryWrapper<KnowledgeChunkDO>()
                .select(
                        KnowledgeChunkDO::getId,
                        KnowledgeChunkDO::getKbId,
                        KnowledgeChunkDO::getDocId,
                        KnowledgeChunkDO::getChunkIndex,
                        KnowledgeChunkDO::getContent,
                        KnowledgeChunkDO::getCharCount,
                        KnowledgeChunkDO::getTokenCount,
                        KnowledgeChunkDO::getCreateTime,
                        KnowledgeChunkDO::getUpdateTime,
                        KnowledgeChunkDO::getCreateBy,
                        KnowledgeChunkDO::getUpdateBy,
                        KnowledgeChunkDO::getEnabled
                )
                .eq(KnowledgeChunkDO::getDocId, document.getId())
                .eq(KnowledgeChunkDO::getKbId, document.getKbId());
    }

    private KnowledgeChunkVO toChunkVO(KnowledgeChunkDO chunk) {
        return KnowledgeChunkVO.builder()
                .id(chunk.getId())
                .kbId(chunk.getKbId())
                .docId(chunk.getDocId())
                .chunkIndex(chunk.getChunkIndex())
                .content(chunk.getContent())
                .charCount(chunk.getCharCount())
                .tokenCount(chunk.getTokenCount())
                .createTime(chunk.getCreateTime())
                .updateTime(chunk.getUpdateTime())
                .createBy(chunk.getCreateBy())
                .updateBy(chunk.getUpdateBy())
                .enabled(chunk.getEnabled())
                .build();
    }

    /** 替换 PG 全量分块；解析和向量化已经在事务外完成，本方法不加锁。 */
    @Override
    public void saveChunks(String docId, List<DocumentChunk> chunks, List<float[]> vectors, String operator) {
        validateInput(docId, chunks, vectors, operator);

        // 模型调用已经完成；事务仅包含关联信息读取、分块替换和文档阶段更新。
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDocDO document = knowledgeDocMapper.selectById(docId);
            if (document == null) {
                throw new IllegalArgumentException("待保存分块的文档不存在");
            }
            if (!"chunking".equals(document.getStatus())) {
                throw new IllegalStateException("文档未处于分块处理中");
            }
            KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(document.getKbId());
            if (knowledgeBase == null || !StringUtils.hasText(knowledgeBase.getCollection())) {
                throw new IllegalArgumentException("文档所属知识库或 collection 不存在");
            }

            if (!Integer.valueOf(1).equals(document.getEnabled())) {
                throw new IllegalStateException("待保存分块的文档未启用");
            }
            Date now = new Date();
            List<KnowledgeChunkDO> chunkEntities = buildChunkDOs(
                    document, knowledgeBase.getCollection(), chunks, vectors, operator, now);
            // 删除和新分块保存使用同一个事务，任一步失败都会恢复旧 PG 数据。
            knowledgeChunkMapper.physicalDeleteByDocId(docId);
            for (int start = 0; start < chunkEntities.size(); start += INSERT_BATCH_SIZE) {
                int end = Math.min(start + INSERT_BATCH_SIZE, chunkEntities.size());
                List<KnowledgeChunkDO> batch = chunkEntities.subList(start, end);
                knowledgeChunkMapper.insert(batch); //TODO 这里插入失败应该会抛异常
            }

            // 直接调用 BaseMapper 已有方法，不修改 KnowledgeDocMapper。
            LambdaUpdateWrapper<KnowledgeDocDO> update = new LambdaUpdateWrapper<KnowledgeDocDO>()
                    .eq(KnowledgeDocDO::getId, docId)
                    .eq(KnowledgeDocDO::getStatus, "chunking")
                    .set(KnowledgeDocDO::getChunkCount, chunkEntities.size())
                    .set(KnowledgeDocDO::getStatus, "elasticing")
                    .set(KnowledgeDocDO::getUpdateBy, operator)
                    .set(KnowledgeDocDO::getUpdateTime, now);
            if (knowledgeDocMapper.update(null, update) != 1) {
                throw new IllegalStateException("更新文档分块结果失败");
            }
        });
    }

    /** 从 PG 全量重建 ES；所有异常继续向外抛出，失败时文档仍为 elasticing。 */
    @Override
    public void saveChunksInEs(String docId, String operator) {
        if (!StringUtils.hasText(docId) || !StringUtils.hasText(operator)) {
            throw new IllegalArgumentException("docId 和 ES 同步操作者不能为空");
        }
        if (!StringUtils.hasText(chunkIndex) || esBulkSize < 1 || esBulkSize > 1000) {
            throw new IllegalStateException("ES 索引名称不能为空，Bulk 批次大小必须在 1 到 1000 之间");
        }
        KnowledgeDocDO document = knowledgeDocMapper.selectById(docId);
        if (document == null) {
            throw new IllegalArgumentException("待同步 ES 的文档不存在或已删除");
        }
        if (!"elasticing".equals(document.getStatus()) || !Integer.valueOf(1).equals(document.getEnabled())) {
            throw new IllegalStateException("ES 同步要求文档已启用且处于 elasticing 状态");
        }
        if (document.getChunkCount() == null || document.getChunkCount() < 1) {
            throw new IllegalStateException("文档没有已完成的分块");
        }
        if (knowledgeBaseMapper.selectById(document.getKbId()) == null) {
            throw new IllegalStateException("ES 同步文档所属知识库不存在");
        }

        // 每次补写都重建该文档的 ES 数据，保留 PG，避免旧 Chunk ID 或上次部分成功的数据残留。
        deleteChunksInEs(docId);

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
                    .eq(KnowledgeChunkDO::getDocId, docId)
                    .eq(KnowledgeChunkDO::getKbId, document.getKbId())
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
                throw new IllegalStateException("ES Bulk 请求失败，docId=" + docId, exception);
            }
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("ES Chunk 写入失败，docId={}，chunkId={}，status={}，reason={}",
                                docId, item.id(), item.status(), item.error().reason());
                    }
                }
                throw new IllegalStateException("ES Bulk 存在失败条目，docId=" + docId);
            }

            indexedCount += chunks.size();
            lastChunkIndex = chunks.get(chunks.size() - 1).getChunkIndex();
        }

        // 5. 对照 PG 文档记录核对本次处理数量；重试会清理该文档 ES 后重新写入全部批次。
        if (indexedCount != document.getChunkCount()) {
            throw new IllegalStateException("ES 同步的 Chunk 数量与 PG 文档记录不一致，docId=" + docId);
        }
        // 所有 ES 批次确认成功后才推进文档状态，ES 请求不加入 PG 保存事务。
        LambdaUpdateWrapper<KnowledgeDocDO> update = new LambdaUpdateWrapper<KnowledgeDocDO>()
                .eq(KnowledgeDocDO::getId, docId)
                .eq(KnowledgeDocDO::getStatus, "elasticing")
                .set(KnowledgeDocDO::getStatus, "success")
                .set(KnowledgeDocDO::getUpdateBy, operator)
                .set(KnowledgeDocDO::getUpdateTime, new Date());
        if (knowledgeDocMapper.update(null, update) != 1) {
            throw new IllegalStateException("ES 已写入，但更新文档成功状态失败，docId=" + docId);
        }
        // Bulk 成功表示写入已确认，搜索可见性仍取决于 ES refresh。
        log.info("ES Chunk 同步完成，docId={}，chunkCount={}", docId, indexedCount);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        knowledgeChunkMapper.physicalDeleteByDocId(docId);
        deleteChunksInEs(docId);
    }

    /** ES 补写与文档删除共用清理逻辑；这里只删除 ES，不操作 PG 或 RustFS。 */
    private void deleteChunksInEs(String docId) {
        if (!StringUtils.hasText(chunkIndex)) {
            throw new IllegalStateException("ES Chunk 索引配置不能为空");
        }
        try {
            if (!elasticsearchClient.indices().exists(request -> request.index(chunkIndex)).value()) {
                return;
            }
            if (elasticsearchClient.indices().refresh(request -> request
                    .index(chunkIndex)).shards().failed().intValue() > 0) {
                throw new IllegalStateException("ES 文档分块清理前刷新存在失败分片");
            }
            DeleteByQueryResponse response = elasticsearchClient.deleteByQuery(request -> request
                    .index(chunkIndex)
                    .conflicts(Conflicts.Abort)
                    .waitForCompletion(true)
                    .refresh(true)
                    .query(query -> query.term(term -> term
                            .field("docId")
                            .value(docId))));
            if (!Boolean.FALSE.equals(response.timedOut()) || !response.failures().isEmpty()
                    || response.versionConflicts() == null || response.versionConflicts() > 0) {
                throw new IllegalStateException("ES 文档分块删除超时、结果不完整或存在失败项");
            }
            CountResponse remaining = elasticsearchClient.count(request -> request
                    .index(chunkIndex)
                    .query(query -> query.term(term -> term
                            .field("docId")
                            .value(docId))));
            if (remaining.shards().failed().intValue() > 0 || remaining.count() > 0) {
                throw new IllegalStateException("ES 文档分块清理后仍有残余数据或核对失败");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("ES 文档分块删除失败，docId=" + docId, exception);
        }
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
