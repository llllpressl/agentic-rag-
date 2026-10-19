package com.llll.rag.knowledge.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MqTopicEnum {

    DOC_CHUNK(MqTopicEnum.DOC_CHUNK_TOPIC, MqTopicEnum.DOC_CHUNK_TAG),
    DOC_CHUNK_ES(MqTopicEnum.DOC_CHUNK_TOPIC, MqTopicEnum.DOC_CHUNK_ES_TAG);

    // 消费者注解需要编译期常量。
    public static final String DOC_CHUNK_TOPIC = "knowledge-doc-chunk-topic";
    public static final String DOC_CHUNK_TAG = "CHUNK";
    public static final String DOC_CHUNK_ES_TAG = "ES_STORE";

    private final String topic;
    private final String tag;

    public String getDestination() {
        return topic + ":" + tag;
    }
}
