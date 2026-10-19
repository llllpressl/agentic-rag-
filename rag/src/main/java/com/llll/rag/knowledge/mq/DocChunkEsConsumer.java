package com.llll.rag.knowledge.mq;

import com.llll.rag.knowledge.mq.event.DocChunkEsEvent;
import com.llll.rag.knowledge.enums.MqTopicEnum;
import com.llll.rag.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/** 原独立 ES 消费者停用。新流程由 DocChunkConsumer 直接调用 ES 方法。
@RequiredArgsConstructor
@Component
@RocketMQMessageListener(
        topic = MqTopicEnum.DOC_CHUNK_TOPIC,
        consumerGroup = "knowledge-document-chunk-es-group",
        selectorExpression = MqTopicEnum.DOC_CHUNK_ES_TAG
)
public class DocChunkEsConsumer implements RocketMQListener<DocChunkEsEvent> {

    private final KnowledgeChunkService knowledgeChunkService;

    @Override
    public void onMessage(DocChunkEsEvent message) {

        // 服务内校验消息并执行 Bulk；失败继续向外抛出，由 MQ 触发消费重试。
        knowledgeChunkService.saveChunksInEs(message);
    }
}
*/

/** 保留原类名供增量编译覆盖旧 class；不注册为 Bean，也不监听 MQ。 */
public class DocChunkEsConsumer {
}
