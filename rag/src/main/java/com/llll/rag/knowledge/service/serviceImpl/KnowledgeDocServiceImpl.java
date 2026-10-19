package com.llll.rag.knowledge.service.serviceImpl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llll.framwork.context.UserContext;
import com.llll.rag.knowledge.controller.request.KnowledgeDocUpdateRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocVO;
import com.llll.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.llll.rag.knowledge.dao.entity.KnowledgeDocDO;
import com.llll.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.llll.rag.knowledge.dao.mapper.KnowledgeDocMapper;
import com.llll.rag.knowledge.enums.MqTopicEnum;
import com.llll.rag.knowledge.mq.event.DocChunkEvent;
import com.llll.rag.knowledge.service.KnowledgeDocService;
import com.llll.rag.knowledge.service.KnowledgeBaseOperationLock;
import com.llll.rag.knowledge.storage.RustFsStorageService;
import com.llll.rag.knowledge.storage.StorageUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final RustFsStorageService rustFsStorageService;
    private final TransactionTemplate transactionTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final KnowledgeBaseOperationLock knowledgeBaseOperationLock;

    private final static String Topic = MqTopicEnum.DOC_CHUNK.getDestination();

    @Override
    public void startChunk(String docId) {
        // 1. 校验doc是否能够进行分块任务
        KnowledgeDocDO knowledgeDocDO = docValidate(docId);
        String username = UserContext.getUser().getUsername();
        // 2.
        transactionTemplate.execute(status -> {
            int updated = knowledgeDocMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocDO>()
                            .eq(KnowledgeDocDO::getId, docId)
                            .eq(KnowledgeDocDO::getStatus, "pending")
                            .set(KnowledgeDocDO::getStatus, "running")
                            .set(KnowledgeDocDO::getUpdateBy, username)
            );
            if (updated == 0) {
                throw new RuntimeException("修改状态失败，该文档正在分块中");
            }
            return null;
        });

        DocChunkEvent event = DocChunkEvent.builder()
                .docId(docId)
                .kbId(knowledgeDocDO.getKbId())
                .user(username)
                .build();
        /// todo 待做任务补偿
        rocketMQTemplate.syncSend(
                Topic,
                event
        );
    }

    /**
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
    */

    @Override
    public KnowledgeDocVO upload(String kbId, MultipartFile file) {
        if (UserContext.getUser() == null || !StringUtils.hasText(UserContext.getUser().getUsername())) {
            throw new IllegalStateException("请先登录");
        }
        String operator = UserContext.getUser().getUsername();
        // 互斥覆盖文件上传与登记，删除不能越过仍在上传的请求提前清空前缀。
        return knowledgeBaseOperationLock.execute(kbId, () -> uploadWithLock(kbId, file, operator));
    }

    private KnowledgeDocVO uploadWithLock(String kbId, MultipartFile file, String operator) {
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if (knowledgeBase == null) {
            throw new IllegalArgumentException("知识库不存在或已删除");
        }
        StorageUploadResult uploadResult;
        try {
            uploadResult = rustFsStorageService.upload(knowledgeBase.getCollection(), file);
        } catch (IOException exception) {
            throw new IllegalStateException("文档上传失败", exception);
        }

        KnowledgeDocDO knowledgeDoc = KnowledgeDocDO.builder()
                .kbId(kbId)
                .docName(uploadResult.originalFilename().trim())
                .chunkCount(0)
                .fileUrl(uploadResult.objectKey())
                .fileType(uploadResult.fileType())
                .mimeType(uploadResult.contentType())
                .fileSize(uploadResult.size())
                .createBy(operator)
                .updateBy(operator)
                .status("pending")
                .enabled(1)
                .deleted(0)
                .build();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                KnowledgeBaseDO current = knowledgeBaseMapper.selectIncludingDeletedForUpdate(kbId);
                if (current == null || !Integer.valueOf(0).equals(current.getDeleted())) {
                    throw new IllegalStateException("知识库已删除，不能登记文档");
                }
                if (knowledgeDocMapper.insert(knowledgeDoc) != 1) {
                    throw new IllegalStateException("文档登记失败");
                }
            });
        } catch (RuntimeException exception) {
            try {
                rustFsStorageService.delete(uploadResult.objectKey());
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
                log.error("文档登记失败，文件补偿删除失败，kbId={}，objectKey={}",
                        kbId, uploadResult.objectKey(), cleanupException);
            }
            throw exception;
        }

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocDO knowledgeDoc = docValidate(docId);
        String username = UserContext.getUser().getUsername();
        // TODO 解决删文档以及chunk 后 删除 rustfs文件的事务不一致问题
        // 1. 物理删除分块，包括分块中保存的向量。
        // 2. 物理删除 RustFS 原文件；失败时抛出异常，回滚数据库操作。
        rustFsStorageService.delete(knowledgeDoc.getFileUrl());
        // 3. 逻辑删除文档信息并记录删除人、删除时间。RustFS 文件无法随数据库回滚恢复。
        // TODO 代办
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

    ///------------------------------------------------- 工具方法

    private KnowledgeDocDO docValidate(String docId){
        if(!StringUtils.hasText(docId)) throw new RuntimeException("docId 不能为空");
        KnowledgeDocDO knowledgeDocDO = knowledgeDocMapper.selectById(docId);
        if(knowledgeDocDO == null) throw new RuntimeException("没有相关文档");
        if(knowledgeDocDO.getEnabled() == 0) throw new RuntimeException("文档没有被启用");
        if(knowledgeDocDO.getDeleted() == 1) throw new RuntimeException("文档被删除了");
        if(knowledgeDocDO.getStatus().equals("running")) throw new RuntimeException("文档正在进行分块，不能做删除，更新，和分块");
        return knowledgeDocDO;
    }

}
