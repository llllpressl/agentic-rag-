package com.llll.rag.knowledge.controller;

import com.llll.framwork.result.Result;
import com.llll.rag.knowledge.controller.request.KnowledgeDocPageRequest;
import com.llll.rag.knowledge.controller.request.KnowledgeDocUpdateRequest;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocPageVO;
import com.llll.rag.knowledge.controller.vo.KnowledgeDocVO;
import com.llll.rag.knowledge.service.KnowledgeDocService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class KnowledgeDocController {

    private final KnowledgeDocService knowledgeDocService;

    /// 核心。
    @PostMapping("/knowledge/document/{docId}/chunk")
    public Result<Void> startChunk(@PathVariable String docId){
        knowledgeDocService.startChunk(docId);
        return Result.success();
    }

    /** 同步补写 ES；仅在全部写入并更新文档状态成功后返回。 */
    @PostMapping("/knowledge/document/{docId}/es-sync")
    public Result<Void> syncChunksToEs(@PathVariable String docId) {
        knowledgeDocService.syncChunksToEs(docId);
        return Result.success();
    }

    @PostMapping(value = "/knowledge/base/{kbId}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocVO> upload(
            @PathVariable String kbId,
            @RequestParam("file") MultipartFile file) {
        return Result.success(knowledgeDocService.upload(kbId, file));
    }

    @DeleteMapping("/knowledge/document/{doc-id}")
    public Result<Void> delete(@PathVariable("doc-id") String docId){
        knowledgeDocService.delete(docId);
        return Result.success();
    }

    ///其实本质只能改名
    @PutMapping("/knowledge/document/{doc-id}")
    public Result<Void> update(@PathVariable("doc-id") String docId,
                               @RequestBody KnowledgeDocUpdateRequest request){
        knowledgeDocService.update(docId, request);
        return Result.success();
    }

    /** 查询未删除文档的详情。 */
    @GetMapping("/knowledge/document/{docId}")
    public Result<KnowledgeDocVO> detail(@PathVariable String docId) {
        return Result.success(knowledgeDocService.detail(docId));
    }

    /** 按知识库和文档名称分页查询未删除的文档。 */
    @GetMapping("/knowledge/document/page")
    public Result<KnowledgeDocPageVO> page(@ModelAttribute KnowledgeDocPageRequest request) {
        return Result.success(knowledgeDocService.page(request));
    }

}
