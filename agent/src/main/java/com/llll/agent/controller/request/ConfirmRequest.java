package com.llll.agent.controller.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConfirmRequest {

    private String conversationId;

    private String messageId;

    private boolean approval;
}
