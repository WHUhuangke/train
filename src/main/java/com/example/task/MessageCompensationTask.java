// MessageCompensationTask.java - 消息补偿定时任务
package com.example.task;

import com.example.mapper.MessageLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MessageCompensationTask {

    @Autowired
    private MessageLogMapper messageLogMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 60000)
    public void compensate() {
        List<Map<String, Object>> pending = messageLogMapper.selectPendingMessages();
        for (Map<String, Object> log : pending) {
            try {
                String content = (String) log.get("content");
                Map<String, Object> msg = objectMapper.readValue(content, Map.class);
                rabbitTemplate.convertAndSend("ticket.exchange", "ticket.book", msg);
                messageLogMapper.updateStatus((String) log.get("message_id"), 1);
            } catch (Exception e) {
                // 重试失败，记录日志
            }
        }
    }
}
