package com.llll.rag.knowledge.service.serviceImpl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.llll.framwork.context.UserContext;
import com.llll.rag.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.llll.rag.knowledge.service.KnowledgeBaseService;
import com.llll.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import com.llll.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeDocMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.llll.rag.knowledge.controller.request.KnowledgeBasePageRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeBaseVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeBasePageVO;
import com.llll.rag.knowledge.service.KnowledgeBaseCleanupException;
import com.llll.rag.knowledge.service.KnowledgeBaseOperationLock;
import com.llll.rag.knowledge.storage.RustFsStorageService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch._types.Conflicts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final RustFsStorageService rustFsStorageService;
    private final ElasticsearchClient elasticsearchClient;
    private final TransactionTemplate transactionTemplate;
    private final KnowledgeBaseOperationLock knowledgeBaseOperationLock;

    @Value("${knowledge.elasticsearch.chunk-index:knowledge_chunk}")
    private String chunkIndex;

    @Override
    public String create(KnowledgeBaseCreateRequest requestParam) {
        /// 1. 校验是否被重命名
        Long name = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, requestParam.getName().trim())
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if(name > 0){
            throw new RuntimeException("你的知识库名称重名了");
        }
        Long collection = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getCollection, requestParam.getCollection().trim())
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if(collection > 0){
            throw new RuntimeException("你的知识库集合名重名了");
        }

        /// 2. 构造 知识库实体， 并入库
        KnowledgeBaseDO knowledgeBase = KnowledgeBaseDO.builder()
                .name(requestParam.getName())
                .collection(requestParam.getCollection())
                .embeddingModel(requestParam.getEmbeddingModel())
                .createBy(UserContext.getUser().getUsername())
                .deleted(0)
                .build();
        knowledgeBaseMapper.insert(knowledgeBase);

        /// 3. 返回
        return String.valueOf(knowledgeBase.getId());
    }

    @Override
    public void update(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if(knowledgeBase == null){
            throw new RuntimeException("知识库不存在，改你马");
        }
        if(StringUtils.isBlank(requestParam.getName())){
            throw new RuntimeException("空名称改你马啊");
        }

        Long name = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, requestParam.getName().trim())
                        .ne(KnowledgeBaseDO::getId, kbId)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if(name > 0){
            throw new RuntimeException("你要重命名的名字 重名了，改你马");
        }

        knowledgeBase.setUpdateBy(UserContext.getUser().getUsername());
        knowledgeBase.setName(requestParam.getName());
        knowledgeBaseMapper.updateById(knowledgeBase);
    }

    @Override
    public void delete(String kbId) {
        if (StringUtils.isBlank(kbId)) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        String operator = requireOperator();
        knowledgeBaseOperationLock.execute(kbId, () -> {
            KnowledgeBaseDO knowledgeBase = logicalDelete(kbId, operator);
            // PG 已提交；外部清理失败不恢复 KB，保留 doc 便于再次清理和定位文件。
            try {
                validateCleanupFiles(knowledgeBase);
                rustFsStorageService.deleteCollection(knowledgeBase.getCollection());
                deleteChunksFromEs(kbId);
                transactionTemplate.executeWithoutResult(status -> {
                    knowledgeChunkMapper.physicalDeleteByKbId(kbId);
                    knowledgeDocMapper.physicalDeleteByKbId(kbId);
                });
            } catch (Exception exception) {
                log.error("知识库已逻辑删除，残余清理失败，kbId={}", kbId, exception);
                throw new KnowledgeBaseCleanupException(
                        "知识库已逻辑删除，残余清理未完成，请再次调用删除接口重试", exception);
            }
            return null;
        });
    }

    private KnowledgeBaseDO logicalDelete(String kbId, String operator) {
        KnowledgeBaseDO knowledgeBase = transactionTemplate.execute(status -> {
            KnowledgeBaseDO current = knowledgeBaseMapper.selectIncludingDeletedForUpdate(kbId);
            if (current == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
            }
            Long docCount = knowledgeDocMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocDO>()
                    .eq(KnowledgeDocDO::getKbId, kbId)
                    .eq(KnowledgeDocDO::getDeleted, 0));
            if (docCount > 0) {
                throw new IllegalStateException("知识库内仍有未删除的文档，请先删除文档");
            }

            Date now = new Date();
            if (Integer.valueOf(0).equals(current.getDeleted())) {
                if (knowledgeBaseMapper.logicalDeleteWithAudit(kbId, operator, now) != 1) {
                    throw new IllegalStateException("知识库逻辑删除失败");
                }
            }
            knowledgeChunkMapper.logicalDeleteByKbId(kbId, operator, now);
            return current;
        });
        if (knowledgeBase == null) {
            throw new IllegalStateException("知识库删除事务未返回结果");
        }
        return knowledgeBase;
    }

    /** 文档只读元数据，包含已删除记录；不把所有正文或向量加载到内存。 */
    private void validateCleanupFiles(KnowledgeBaseDO knowledgeBase) {
        String collection = knowledgeBase.getCollection();
        if (collection == null || !collection.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalStateException("知识库 collection 不合法，无法清理文件");
        }
        String prefix = collection + "/";
        String afterId = null;
        while (true) {
            List<KnowledgeDocDO> documents = knowledgeDocMapper.selectCleanupPage(
                    knowledgeBase.getId(), afterId, 100);
            if (documents.isEmpty()) {
                break;
            }
            for (KnowledgeDocDO document : documents) {
                if (StringUtils.isNotBlank(document.getFileUrl())
                        && !document.getFileUrl().startsWith(prefix)) {
                    throw new IllegalStateException("文档文件不在知识库 collection 下，请核对文件归属");
                }
            }
            afterId = documents.get(documents.size() - 1).getId();
        }
    }

    private void deleteChunksFromEs(String kbId) throws IOException {
        if (StringUtils.isBlank(chunkIndex)) {
            throw new IllegalStateException("ES Chunk 索引配置不能为空");
        }
        // 尚未建立索引时没有可清理的数据；连接失败和权限失败仍向外抛出。
        if (!elasticsearchClient.indices().exists(request -> request.index(chunkIndex)).value()) {
            return;
        }
        // 先 refresh，确保此前已确认的 Bulk 数据进入本次按查询删除的快照。
        if (elasticsearchClient.indices().refresh(request -> request
                .index(chunkIndex)).shards().failed().intValue() > 0) {
            throw new IllegalStateException("ES 清理前刷新存在失败分片");
        }
        DeleteByQueryResponse response = elasticsearchClient.deleteByQuery(request -> request
                .index(chunkIndex)
                .conflicts(Conflicts.Abort)
                .waitForCompletion(true)
                .refresh(true)
                .query(query -> query.term(term -> term
                        .field("kbId")
                        .value(kbId))));
        if (!Boolean.FALSE.equals(response.timedOut()) || !response.failures().isEmpty()
                || response.versionConflicts() == null || response.versionConflicts() > 0) {
            throw new IllegalStateException("ES 删除超时、结果不完整或存在失败项");
        }
        CountResponse remaining = elasticsearchClient.count(request -> request
                .index(chunkIndex)
                .query(query -> query.term(term -> term
                        .field("kbId")
                        .value(kbId))));
        if (remaining.shards().failed().intValue() > 0 || remaining.count() > 0) {
            throw new IllegalStateException("ES 清理后仍有残余数据或核对失败");
        }
    }

    @Override
    public KnowledgeBaseVO detail(String kbId) {
        requireOperator();
        if (StringUtils.isBlank(kbId)) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if (knowledgeBase == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或已删除");
        }
        return toVO(knowledgeBase);
    }

    @Override
    public KnowledgeBasePageVO page(KnowledgeBasePageRequest request) {
        requireOperator();
        if (request == null || request.getPageNo() == null || request.getPageSize() == null
                || request.getPageNo() < 1 || request.getPageSize() < 1 || request.getPageSize() > 100) {
            throw new IllegalArgumentException("页码须大于等于 1，每页数量须在 1 到 100 之间");
        }
        String name = null;
        if (StringUtils.isNotBlank(request.getName())) {
            name = request.getName().trim();
        }
        LambdaQueryWrapper<KnowledgeBaseDO> countQuery = new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getDeleted, 0)
                .like(name != null, KnowledgeBaseDO::getName, name);
        Long total = knowledgeBaseMapper.selectCount(countQuery);
        long offset = (long) (request.getPageNo() - 1) * request.getPageSize();
        List<KnowledgeBaseVO> records = new ArrayList<>();
        if (offset < total) {
            List<KnowledgeBaseDO> knowledgeBases = knowledgeBaseMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeBaseDO>()
                            .eq(KnowledgeBaseDO::getDeleted, 0)
                            .like(name != null, KnowledgeBaseDO::getName, name)
                            .orderByDesc(KnowledgeBaseDO::getCreateTime)
                            .orderByDesc(KnowledgeBaseDO::getId)
                            // 仅拼接已校验的分页数字，沿用现有会话分页方式。
                            .last("LIMIT " + request.getPageSize() + " OFFSET " + offset));
            for (KnowledgeBaseDO knowledgeBase : knowledgeBases) {
                records.add(toVO(knowledgeBase));
            }
        }
        return KnowledgeBasePageVO.builder()
                .records(records)
                .total(total)
                .page(request.getPageNo())
                .size(request.getPageSize())
                .build();
    }

    private KnowledgeBaseVO toVO(KnowledgeBaseDO knowledgeBase) {
        return KnowledgeBaseVO.builder()
                .id(knowledgeBase.getId())
                .name(knowledgeBase.getName())
                .collection(knowledgeBase.getCollection())
                .embeddingModel(knowledgeBase.getEmbeddingModel())
                .createTime(knowledgeBase.getCreateTime())
                .updateTime(knowledgeBase.getUpdateTime())
                .createBy(knowledgeBase.getCreateBy())
                .updateBy(knowledgeBase.getUpdateBy())
                .build();
    }

    private String requireOperator() {
        if (UserContext.getUser() == null || StringUtils.isBlank(UserContext.getUser().getUsername())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return UserContext.getUser().getUsername();
    }
}
