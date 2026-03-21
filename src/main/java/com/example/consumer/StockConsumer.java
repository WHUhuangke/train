package com.example.consumer;

import com.example.mapper.MessageLogMapper;
import com.example.mapper.TrainTicketStockMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.example.service.OrderService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.config.RabbitMQConfig.ORDER_NOTIFY_ROUTING_KEY;

/**
 * 库存扣减消费者。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>消费 ticket.stock.queue 执行库存扣减与缓存清理。</li>
 *   <li>库存扣减成功后将订单状态更新为 2（完成）。</li>
 *   <li>在同一数据库事务中写入「订单完成通知 outbox」。</li>
 * </ul>
 */
@Slf4j
@Component
public class StockConsumer {

    @Autowired
    private TrainTicketStockMapper stockMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private MessageLogMapper messageLogMapper;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = "ticket.stock.queue", ackMode = "MANUAL")
    public void handleStockUpdate(Map<String, Object> msg, Channel channel, Message message) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = (String) msg.get("messageId");
        try {
            // 处理旧消息（无messageId）
            if (messageId == null) {
                log.warn("收到无messageId的旧消息，直接确认: {}", msg);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 幂等性检查（当前消息是否已处理完成）
            Integer status = messageLogMapper.getStatus(messageId);
            if (status != null && status == 2) {
                log.info("消息已处理，跳过: {}", messageId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            if (!(boolean) msg.get("success")) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            Long orderId = Long.parseLong(msg.get("orderId").toString());
            Long trainId = Long.parseLong(msg.get("trainId").toString());
            int sellStart = (int) msg.get("sellStart");
            int sellEnd = (int) msg.get("sellEnd");
            int seatType = (int) msg.get("seatType");

            String bitmap = (String) msg.get("snapshotBitmap");
            int startIndex = 0, endIndex = bitmap.length();

            for (int i = sellStart - 1; i >= 0; i--) {
                if (bitmap.charAt(i) == '1') {
                    startIndex = i + 1;
                    break;
                }
            }
            for (int i = sellEnd; i < bitmap.length(); i++) {
                if (bitmap.charAt(i) == '1') {
                    endIndex = i;
                    break;
                }
            }

            stockMapper.decreaseAffectedStocks(trainId, sellStart, sellEnd, startIndex, endIndex, seatType);

            // 清理余票缓存，让查询侧回源 DB 后重建缓存
            String pattern = "ticket:stock:*:" + trainId + ":" + seatType + ":*";
            Set<String> cacheKeys = redisTemplate.keys(pattern);
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                redisTemplate.delete(cacheKeys);
            }

            // 1) 订单状态更新为 2（订单处理完成）
            orderService.updateStatus(orderId, 2);

            // 2) 在同一事务中写通知 outbox（status=0 待补偿任务投递）
            String notifyMessageId = UUID.randomUUID().toString();
            Map<String, Object> notifyPayload = new HashMap<>();
            notifyPayload.put("eventType", "ORDER_NOTIFY");
            notifyPayload.put("routingKey", ORDER_NOTIFY_ROUTING_KEY);
            notifyPayload.put("messageId", notifyMessageId);
            notifyPayload.put("orderId", orderId);
            notifyPayload.put("userId", msg.get("userId"));
            notifyPayload.put("trainId", trainId);
            notifyPayload.put("seatType", seatType);
            notifyPayload.put("sellStart", sellStart);
            notifyPayload.put("sellEnd", sellEnd);
            notifyPayload.put("finishedAt", LocalDateTime.now().toString());
            String notifyJson = objectMapper.writeValueAsString(notifyPayload);
            messageLogMapper.insert(notifyMessageId, notifyJson, 0, LocalDateTime.now());

            // 标记当前 stock 消息消费完成
            messageLogMapper.updateStatus(messageId, 2);

            channel.basicAck(deliveryTag, false);
            log.info("库存更新并写通知outbox成功: stockMessageId={}, notifyMessageId={}", messageId, notifyMessageId);

        } catch (Exception e) {
            log.error("消息消费失败: {}", messageId, e);

            // 这里建议是否 requeue 取决于你的重试策略
            // true: 重新入队，可能重复投递
            // false: 丢给死信队列（前提是已配置 DLX）
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
