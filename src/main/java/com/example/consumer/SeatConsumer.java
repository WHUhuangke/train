package com.example.consumer;

import com.example.entity.TrainSeat;
import com.example.mapper.MessageLogMapper;
import com.example.mapper.TrainSeatMapper;
import com.example.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.example.config.RabbitMQConfig.STOCK_ROUTING_KEY;

@Slf4j
@Component
public class SeatConsumer {

    @Autowired
    private SeatTxService seatTxService;

    @RabbitListener(queues = "ticket.book.queue", ackMode = "MANUAL")
    public void handleBooking(Map<String, Object> msg, Channel channel, Message message) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Object orderId = msg.get("orderId");

            int length = sellEnd - sellStart;
            String requiredZeros = fillString('0', length);
            String replacementOnes = fillString('1', length);

            // 3. 在分布式锁保护下查询座位
            TrainSeat seat = seatMapper.selectById(seatId);
//            if (seat == null) {
//                log.error("座位不存在, seatId={}", seatId);
//                orderService.markOrderFailed(orderId);
//
//                // Redis对账：删除异常缓存
//                reconcileRedisSeat(trainId, seatType, seatId, null);
//
//                channel.basicAck(deliveryTag, false);
//                return;
//            }

            // 4. 原子更新数据库位图
            int updated = seatMapper.updateSeatBitmapAtomic(
                    seatId,
                    sellStart + 1,
                    length,
                    requiredZeros,
                    replacementOnes
            );

            if (updated > 0) {
                // 5. 读取最新数据库快照
                String snapshotBitmap = seatMapper.selectBitmapBySeatId(seatId);

                // 6. 更新订单状态为成功
                orderService.markOrderSuccess(orderId);

                // 7. Redis对账：以数据库最新状态为准
                reconcileRedisSeat(trainId, seatType, seatId, snapshotBitmap);

                // 8. 组装下游消息
                String messageId = UUID.randomUUID().toString();
                msg.put("success", true);
                msg.put("messageId", messageId);
                msg.put("routingKey", STOCK_ROUTING_KEY);
                msg.put("snapshotBitmap", snapshotBitmap);

                String content = objectMapper.writeValueAsString(msg);
                messageLogMapper.insert(messageId, content, 0, LocalDateTime.now());

                // 当前消息确认消费成功
                channel.basicAck(deliveryTag, false);

                // 发下游库存/后续处理消息
                try {
                    rabbitTemplate.convertAndSend("ticket.exchange", "ticket.stock", msg);
                    messageLogMapper.updateStatus(messageId, 1);
                } catch (Exception e) {
                    log.error("发送消息失败，等待补偿: {}", messageId, e);
                }

            } else {
                // 位图更新失败：通常表示目标区间已被占用，订单置失败
                orderService.markOrderFailed(orderId);

                String snapshotBitmap = seatMapper.selectBitmapBySeatId(seatId);
                msg.put("success", false);
                msg.put("snapshotBitmap", snapshotBitmap);

                // Redis对账：以数据库状态为准
                reconcileRedisSeat(trainId, seatType, seatId, snapshotBitmap);

                channel.basicAck(deliveryTag, false);
            }

        } catch (Exception e) {
            log.error("处理失败", e);
            channel.basicNack(deliveryTag, false, false);
        } finally {
            if (locked && lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Redis座位对账
     * 以数据库bitmap为准，修正Redis中的seat bitmap
     *
     * @param trainId 车次ID
     * @param seatType 座位类型
     * @param seatId 座位ID
     * @param dbBitmap 数据库中的bitmap；如果为null则删除异常缓存
     */
    private void reconcileRedisSeat(Long trainId, Integer seatType, Long seatId, String dbBitmap) {
        String redisSeatKey = "seat:" + trainId + ":" + seatType + ":" + seatId;
        try {
            seatTxService.handleBookingTx(msg);
            channel.basicAck(deliveryTag, false);
            log.info("订票消息消费成功: orderId={}", orderId);
        } catch (Exception e) {
            log.error("订票消息消费失败: orderId={}", orderId, e);

            // 建议结合死信队列策略决定是否 requeue
            channel.basicNack(deliveryTag, false, false);
        }
    }
}