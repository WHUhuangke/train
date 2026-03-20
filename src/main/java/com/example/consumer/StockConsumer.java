// StockConsumer.java
package com.example.consumer;

import com.example.mapper.TrainTicketStockMapper;
import com.example.mapper.MessageLogMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class StockConsumer {

    @Autowired
    private TrainTicketStockMapper stockMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private MessageLogMapper messageLogMapper;

    @RabbitListener(queues = "ticket.stock.queue", ackMode = "MANUAL")
    @Transactional(rollbackFor = Exception.class)
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

            // 幂等性检查
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

            Long trainId = Long.parseLong(msg.get("trainId").toString());
            Long seatId = Long.parseLong(msg.get("seatId").toString());
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
            String pattern = "ticket:stock:*:" + trainId + ":" + seatType + ":*";
            Set<String> cacheKeys = redisTemplate.keys(pattern);
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                redisTemplate.delete(cacheKeys);
            }
            messageLogMapper.updateStatus(messageId, 2);

            channel.basicAck(deliveryTag, false);
            log.info("库存更新成功: {}", messageId);

        } catch (Exception e) {
            log.error("处理失败: {}", messageId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}