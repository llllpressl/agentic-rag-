package com.llll.rag.knowledge.controller;

import com.llll.framwork.result.Result;
import com.llll.rag.knowledge.controller.request.KnowledgeDocUpdateRequest;
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
    /** @PostMapping() */
    @PostMapping("/knowledge/document/{docId}/chunk")
    public Result<Void> startChunk(@PathVariable String docId){
        knowledgeDocService.startChunk(docId);
        return Result.success();
    }


    /** @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) */
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

}
