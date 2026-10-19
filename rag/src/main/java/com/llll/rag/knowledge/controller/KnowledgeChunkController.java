package com.llll.rag.knowledge.controller;

import com.llll.framwork.result.Result;
import com.llll.rag.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeChunkPageVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeChunkVO;
import com.llll.rag.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/knowledge/document/{docId}/chunk")
@RequiredArgsConstructor
public class KnowledgeChunkController {

    private final KnowledgeChunkService knowledgeChunkService;

    /** 查看指定文档下的一个分块，不能通过其他文档的 chunkId 跨文档查询。 */
    @GetMapping("/{chunkId}")
    public Result<KnowledgeChunkVO> detail(
            @PathVariable("docId") String docId,
            @PathVariable("chunkId") String chunkId) {
        return Result.success(knowledgeChunkService.detail(docId, chunkId));
    }

    /** 按原文顺序分页查看指定文档的分块正文。 */
    @GetMapping("/page")
    public Result<KnowledgeChunkPageVO> page(
            @PathVariable("docId") String docId,
            @ModelAttribute KnowledgeChunkPageRequest request) {
        return Result.success(knowledgeChunkService.page(docId, request));
    }
}
