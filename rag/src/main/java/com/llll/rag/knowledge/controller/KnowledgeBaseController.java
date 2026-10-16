package com.llll.rag.knowledge.controller;

import com.llll.framwork.result.Result;
import com.llll.rag.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.llll.rag.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class KnowledgeBaseController {


    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建数据库
     * @param request
     * @return
     */
    @PostMapping("/knowledge/base")
    public Result<String> create (@RequestBody KnowledgeBaseCreateRequest request){
        return Result.success(knowledgeBaseService.create(request));
    }

    /**
     * 更新数据库，其实只能更新名字
     * @param kbId
     * @param request
     * @return
     */
    @PutMapping("/knowledge/base/{kb-id}")
    public Result<Void> update (@PathVariable("kb-id") String kbId,
                          @RequestBody KnowledgeBaseUpdateRequest request){
        knowledgeBaseService.update(kbId, request);
        return Result.success();
    }

    /**
     * 删除数据库
     * @param kbId
     * @return
     */
    @DeleteMapping("/knowledge/base/{kb-id}")
    public Result<Void> delete (@PathVariable("kb-id") String kbId){
        knowledgeBaseService.delete(kbId);
        return Result.success();
    }
}
