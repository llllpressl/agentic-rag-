package com.llll.rag.knowledge.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DocChunkEsEvent {

    // 独立消息标识，用于串联发送和消费日志；ES 去重使用 Chunk 主键。
    private String eventId;

    private String docId;

    private String kbId;
}
