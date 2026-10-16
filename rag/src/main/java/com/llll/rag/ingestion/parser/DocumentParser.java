package com.llll.rag.ingestion.parser;

import com.llll.rag.ingestion.model.ParseResult;

import java.io.File;

/**
 * 文档解析策略。路由器根据 supports 选择唯一匹配的实现。
 * 下载、文档状态和数据库写入由业务服务负责。
 */
public interface DocumentParser {

    /**
     * 是否支持文档表保存的格式名，例如 pdf、doc、docx、markdown。
     * 参数是项目保存的格式名，不是 MIME 字符串；文件识别由接入层负责。
     */
    boolean supports(String fileType);

    /**
     * 解析本地文件并构建 Block。不得静默返回截断的正文或部分成功结果。
     * 解析失败时抛出异常，由编排服务记录失败状态和错误信息。
     * 本地源文件由调用方创建和清理，解析器关闭自己打开的流。
     */
    ParseResult parse(File file);
}
