package com.example.task;

import com.example.config.RabbitMQConfig;
import com.example.mapper.MessageLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outbox 补偿任务：定时扫描 t_message_log(status=0) 并投递到 MQ。
 */
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

                // 默认兼容旧数据；新 outbox 建议显式带 routingKey。
                String routingKey = (String) msg.getOrDefault("routingKey", RabbitMQConfig.BOOK_ROUTING_KEY);
                rabbitTemplate.convertAndSend(RabbitMQConfig.TICKET_EXCHANGE, routingKey, msg);
                messageLogMapper.updateStatus((String) log.get("message_id"), 1);
            } catch (Exception e) {
                // 继续等待下一轮补偿
            }
        }
    }
}
