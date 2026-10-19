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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;

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
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if(knowledgeBase == null){
            throw new RuntimeException("没库你删啥");
        }
        Long docs = knowledgeDocMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocDO>()
                        .eq(KnowledgeDocDO::getKbId, kbId)
                        .eq(KnowledgeDocDO::getDeleted,0)
        );
        if(docs != null && docs > 0){
            throw new RuntimeException("先把文档删完再删库");
        }

        String user = UserContext.getUser().getUsername();

        /// 逻辑删除base，
        /// 异步逻辑删除残余 doc， 彻底删除 chunk，彻底删除 对象存储的文件。

        // 当前只允许删除没有未删除文档的知识库，记录删除人和删除时间。
        int updated = knowledgeBaseMapper.update(
                new LambdaUpdateWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getId, knowledgeBase.getId())
                        .set(KnowledgeBaseDO::getDeleted, 1)
                        .set(KnowledgeBaseDO::getUpdateBy, user)
                        .set(KnowledgeBaseDO::getUpdateTime, new Date())
        );
        if (updated != 1) {
            throw new IllegalStateException("知识库删除失败，请刷新后重试");
        }

    }

}
