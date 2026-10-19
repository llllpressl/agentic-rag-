package com.llll.rag.knowledge.service.serviceImpl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llll.framwork.context.UserContext;
import com.llll.rag.knowledge.controller.request.KnowledgeDocPageRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeDocUpdateRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocPageVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocVO;
import com.llll.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import com.llll.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeDocMapper;
import com.llll.rag.knowledge.enums.MqTopicEnum;
import com.llll.rag.knowledge.mq.event.DocChunkEvent;
import com.llll.rag.knowledge.service.KnowledgeDocService;
import com.llll.rag.knowledge.service.KnowledgeChunkService;
/** import com.llll.rag.knowledge.service.KnowledgeBaseOperationLock; */
import com.llll.rag.knowledge.storage.RustFsStorageService;
import com.llll.rag.knowledge.storage.StorageUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
/** import lombok.extern.slf4j.Slf4j; */
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final RustFsStorageService rustFsStorageService;
    private final TransactionTemplate transactionTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final KnowledgeChunkService knowledgeChunkService;

    private final static String Topic = MqTopicEnum.DOC_CHUNK.getDestination();

    @Override
    public void startChunk(String docId) {
        String username = requireOperator();
        KnowledgeDocDO document = docValidate(docId);
        if (!"pending".equals(document.getStatus())) {
            throw new IllegalStateException("只有 pending 文档可以开始分块，暂不支持重新分块");
        }
        if (knowledgeBaseMapper.selectById(document.getKbId()) == null) {
            throw new IllegalArgumentException("文档所属知识库不存在或已删除");
        }

        transactionTemplate.executeWithoutResult(status -> {
            LambdaUpdateWrapper<KnowledgeDocDO> update = new LambdaUpdateWrapper<KnowledgeDocDO>()
                    .eq(KnowledgeDocDO::getId, docId)
                    .eq(KnowledgeDocDO::getStatus, "pending")
                    .set(KnowledgeDocDO::getStatus, "chunking")
                    .set(KnowledgeDocDO::getUpdateBy, username)
                    .set(KnowledgeDocDO::getUpdateTime, new Date());
            if (knowledgeDocMapper.update(null, update) != 1) {
                throw new IllegalStateException("提交分块失败，文档未处于 pending 状态");
            }
        });

        DocChunkEvent event = DocChunkEvent.builder()
                .docId(docId)
                .kbId(document.getKbId())
                .user(username)
                .build();
        // 沿用先提交 PG 状态再发送 MQ 的方式，发送失败后的自动补偿不在本轮范围内。
        rocketMQTemplate.syncSend(Topic, event);
    }

    @Override
    public void syncChunksToEs(String docId) {
        String username = requireOperator();
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        // 不走 docValidate：它保护删除、修改和首次分块，会拦截 elasticing 文档。
        // ES 方法统一校验文档、所属知识库、启用状态及 elasticing 状态。
        knowledgeChunkService.saveChunksInEs(docId, username);
    }

    @SneakyThrows
    @Override
    public KnowledgeDocVO upload(String kbId, MultipartFile file) {
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if(knowledgeBase == null){
            throw new RuntimeException("没建库上传你妈的文档");
        }
        StorageUploadResult uploadResult = rustFsStorageService.upload(knowledgeBase.getCollection(),file);

        KnowledgeDocDO knowledgeDoc = KnowledgeDocDO.builder()
                .kbId(kbId)
                .docName(uploadResult.originalFilename().trim())
                .chunkCount(0)
                .fileUrl(uploadResult.objectKey())
                .fileType(uploadResult.fileType())
                .mimeType(uploadResult.contentType())
                .fileSize(uploadResult.size())
                .createBy(UserContext.getUser().getUsername())
                .updateBy(UserContext.getUser().getUsername())
                .status("pending")
                .enabled(1)
                .deleted(0)
                .build();
        knowledgeDocMapper.insert(knowledgeDoc);
        KnowledgeDocVO vo = new KnowledgeDocVO();
        BeanUtils.copyProperties(knowledgeDoc, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocDO knowledgeDoc = docValidate(docId);
        if (!"pending".equals(knowledgeDoc.getStatus()) && !"success".equals(knowledgeDoc.getStatus())) {
            throw new IllegalStateException("只有 pending 或 success 状态的文档可以删除");
        }
        String username = UserContext.getUser().getUsername();
        // TODO 解决删文档以及chunk 后 删除 rustfs文件的事务不一致问题
        // 1. 物理删除分块，包括分块中保存的向量。
        knowledgeChunkService.delete(docId);
        // 2. 物理删除 RustFS 原文件；失败时抛出异常，回滚数据库操作。
        rustFsStorageService.delete(knowledgeDoc.getFileUrl());
        // 3. 逻辑删除文档信息并记录删除人、删除时间。RustFS 文件无法随数据库回滚恢复。
        LambdaUpdateWrapper<KnowledgeDocDO> wrapper = new LambdaUpdateWrapper<KnowledgeDocDO>()
                .eq(KnowledgeDocDO::getId, docId)
                .in(KnowledgeDocDO::getStatus, "pending", "success")
                .eq(KnowledgeDocDO::getDeleted, 0)
                .set(KnowledgeDocDO::getDeleted, 1)
                .set(KnowledgeDocDO::getUpdateBy, username)
                .set(KnowledgeDocDO::getUpdateTime, new Date());
        if (knowledgeDocMapper.update(null, wrapper) != 1) {
            throw new IllegalStateException("文档删除失败，文档不存在或当前状态不允许删除");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, KnowledgeDocUpdateRequest request) {
        // 校验新名称
        if (request == null || !StringUtils.hasText(request.getDocName())) {
            throw new IllegalArgumentException("文档名称不能为空");
        }
        String newDocName = request.getDocName().trim();
        if (newDocName.codePointCount(0, newDocName.length()) > 255) {
            throw new IllegalArgumentException("文档名称不能超过 255 个字符");
        }
        //校验docDO
        KnowledgeDocDO knowledgeDoc = docValidate(docId);
        // 只更新名称和审计字段，避免覆盖状态、分块数量及文件信息。
        KnowledgeDocDO updateDoc = KnowledgeDocDO.builder()
                .id(knowledgeDoc.getId())
                .docName(newDocName)
                .updateBy(UserContext.getUser().getUsername())
                .updateTime(new Date())
                .build();
        if (knowledgeDocMapper.updateById(updateDoc) != 1) {
            throw new IllegalStateException("文档更新失败，请重试");
        }
    }

    @Override
    public KnowledgeDocVO detail(String docId) {
        requireOperator();
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        KnowledgeDocDO knowledgeDoc = knowledgeDocMapper.selectById(docId);
        if (knowledgeDoc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或已删除");
        }
        if (knowledgeBaseMapper.selectById(knowledgeDoc.getKbId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档所属知识库不存在或已删除");
        }
        return toVO(knowledgeDoc);
    }

    @Override
    public KnowledgeDocPageVO page(KnowledgeDocPageRequest request) {
        requireOperator();
        if (request == null || request.getPageNo() == null || request.getPageSize() == null
                || request.getPageNo() < 1 || request.getPageSize() < 1 || request.getPageSize() > 100) {
            throw new IllegalArgumentException("页码须大于等于 1，每页数量须在 1 到 100 之间");
        }
        if (!StringUtils.hasText(request.getKbId())) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        String kbId = request.getKbId().trim();
        if (knowledgeBaseMapper.selectById(kbId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或已删除");
        }
        String docName = null;
        if (StringUtils.hasText(request.getDocName())) {
            docName = request.getDocName().trim();
        }

        // @TableLogic 自动过滤已删除文档，总数和列表使用相同查询条件。
        LambdaQueryWrapper<KnowledgeDocDO> countQuery = new LambdaQueryWrapper<KnowledgeDocDO>()
                .eq(KnowledgeDocDO::getKbId, kbId)
                .like(docName != null, KnowledgeDocDO::getDocName, docName);
        Long total = knowledgeDocMapper.selectCount(countQuery);
        long offset = (long) (request.getPageNo() - 1) * request.getPageSize();
        List<KnowledgeDocVO> records = new ArrayList<>();
        if (offset < total) {
            List<KnowledgeDocDO> documents = knowledgeDocMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocDO>()
                            .eq(KnowledgeDocDO::getKbId, kbId)
                            .like(docName != null, KnowledgeDocDO::getDocName, docName)
                            .orderByDesc(KnowledgeDocDO::getCreateTime)
                            .orderByDesc(KnowledgeDocDO::getId)
                            // 沿用知识库分页写法，只拼接已校验的分页数字。
                            .last("LIMIT " + request.getPageSize() + " OFFSET " + offset));
            for (KnowledgeDocDO document : documents) {
                records.add(toVO(document));
            }
        }
        return KnowledgeDocPageVO.builder()
                .records(records)
                .total(total)
                .page(request.getPageNo())
                .size(request.getPageSize())
                .build();
    }

    ///------------------------------------------------- 工具方法


    private KnowledgeDocDO docValidate(String docId) {
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        KnowledgeDocDO document = knowledgeDocMapper.selectById(docId);
        if (document == null) {
            throw new IllegalArgumentException("没有相关文档或文档已删除");
        }
        if (!Integer.valueOf(1).equals(document.getEnabled())) {
            throw new IllegalStateException("文档没有被启用");
        }
        if (Integer.valueOf(1).equals(document.getDeleted())) {
            throw new IllegalStateException("文档被删除了");
        }
        // 旧 running 暂不自动迁移，仍视为处理中，保留原有操作限制。
        if ("running".equals(document.getStatus()) || "chunking".equals(document.getStatus())
                || "elasticing".equals(document.getStatus())) {
            throw new IllegalStateException("文档正在处理中，不能删除、更新或再次分块");
        }
        return document;
    }

    private KnowledgeDocVO toVO(KnowledgeDocDO knowledgeDoc) {
        return KnowledgeDocVO.builder()
                .id(knowledgeDoc.getId())
                .kbId(knowledgeDoc.getKbId())
                .docName(knowledgeDoc.getDocName())
                .chunkCount(knowledgeDoc.getChunkCount())
                .fileUrl(knowledgeDoc.getFileUrl())
                .fileType(knowledgeDoc.getFileType())
                .mimeType(knowledgeDoc.getMimeType())
                .fileSize(knowledgeDoc.getFileSize())
                .createTime(knowledgeDoc.getCreateTime())
                .updateTime(knowledgeDoc.getUpdateTime())
                .createBy(knowledgeDoc.getCreateBy())
                .updateBy(knowledgeDoc.getUpdateBy())
                .status(knowledgeDoc.getStatus())
                .enabled(knowledgeDoc.getEnabled())
                .build();
    }

    private String requireOperator() {
        if (UserContext.getUser() == null || !StringUtils.hasText(UserContext.getUser().getUsername())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return UserContext.getUser().getUsername();
    }

}
