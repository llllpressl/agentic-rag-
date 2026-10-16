package com.llll.agent.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentConversationPageVO {

    private List<AgentConversationVO> records;

    private Long total;

    private Integer page;

    private Integer size;
}
