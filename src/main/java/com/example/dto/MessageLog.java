package com.example.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MessageLog {
    private String messageId;
    private String content;
    private Integer status; // 0:待处理, 1:已发MQ, 2:处理成功, -1:处理失败
}
