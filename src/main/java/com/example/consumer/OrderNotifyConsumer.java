package com.example.consumer;

import com.example.service.OrderNotifySseService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订单通知消费者：
 * 1) 消费 ticket.order.notify.queue
 * 2) 推送在线用户 SSE
 * 3) 失败时降级写入站内信缓存
 */
@Slf4j
@Component
public class OrderNotifyConsumer {

    @Autowired
    private OrderNotifySseService orderNotifySseService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @RabbitListener(queues = "ticket.order.notify.queue", ackMode = "MANUAL")
    public void onNotify(Map<String, Object> msg, Channel channel, Message message) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            Long userId = Long.valueOf(msg.get("userId").toString());
            boolean pushed = orderNotifySseService.pushToUser(userId, "order-finished", msg);
            if (!pushed) {
                // 降级：用户离线或 SSE 推送失败，缓存为站内信
                redisTemplate.opsForList().leftPush("user:inbox:" + userId, String.valueOf(msg));
                log.warn("用户不在线，已降级为站内信 userId={}, msg={}", userId, msg);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            // 失败重试
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
