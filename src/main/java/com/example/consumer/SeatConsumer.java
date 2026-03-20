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

@Slf4j
@Component
public class SeatConsumer {

    // 座位数据访问层
    @Autowired
    private TrainSeatMapper seatMapper;

    // 订单服务层（用于幂等校验、状态更新、缓存同步）
    @Autowired
    private OrderService orderService;

    // RabbitMQ消息队列模板
    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 消息日志数据访问层
    @Autowired
    private MessageLogMapper messageLogMapper;

    // JSON序列化工具
    @Autowired
    private ObjectMapper objectMapper;

    // Redis模板（用于座位bitmap对账）
    @Autowired
    private StringRedisTemplate redisTemplate;

    // Redisson客户端（用于分布式锁）
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 车票预订消息消费者
     * 监听队列：ticket.book.queue
     * 功能：处理座位实际占用、订单状态更新、消息可靠性投递
     *
     * 并发控制说明：
     * 1. 先检查订单状态，只有 status=1 才允许继续处理
     * 2. 对 seatId 加分布式锁，锁范围覆盖 查seat + 更新seat + 更新订单 + redis对账
     * 3. tryLock失败则 basicNack(..., true)，让MQ稍后重投
     *
     * @param msg 消息体，包含订单详细信息
     * @param channel RabbitMQ通道
     * @param message RabbitMQ原始消息
     * @throws Exception 处理异常
     */
    @RabbitListener(queues = "ticket.book.queue")
    @Transactional(rollbackFor = Exception.class)
    public void handleBooking(Map<String, Object> msg, Channel channel, Message message) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        log.info("收到订票消息: {}", msg);

        RLock lock = null;
        boolean locked = false;

        try {
            Long orderId = Long.valueOf(msg.get("orderId").toString());
            Long trainId = Long.valueOf(msg.get("trainId").toString());
            Long seatId = Long.valueOf(msg.get("seatId").toString());
            Integer seatType = Integer.valueOf(msg.get("seatType").toString());
            int sellStart = ((Number) msg.get("sellStart")).intValue();
            int sellEnd = ((Number) msg.get("sellEnd")).intValue();

            // 1. 幂等校验：只有待处理订单(status=1)才继续处理
            if (!orderService.isOrderPending(orderId)) {
                log.warn("订单已处理或不存在，跳过重复消费, orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. 获取 seat 级别分布式锁
            String lockKey = "lock:seat:" + seatId;
            lock = redissonClient.getLock(lockKey);

            // 最多等待100ms获取锁，锁租约10秒，防止死锁
            locked = lock.tryLock(100, 10, TimeUnit.MILLISECONDS);

            // 获取锁失败，消息重新入队等待重试
            if (!locked) {
                log.warn("获取分布式锁失败，消息稍后重试, seatId={}, orderId={}", seatId, orderId);
                channel.basicNack(deliveryTag, false, true);
                return;
            }

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
            if (dbBitmap == null || dbBitmap.isEmpty()) {
                redisTemplate.delete(redisSeatKey);
                log.warn("Redis对账: 删除异常座位缓存, key={}", redisSeatKey);
            } else {
                redisTemplate.opsForValue().set(redisSeatKey, dbBitmap);
                log.info("Redis对账成功, key={}, bitmap={}", redisSeatKey, dbBitmap);
            }
        } catch (Exception e) {
            log.error("Redis对账失败, key={}", redisSeatKey, e);
        }
    }

    /**
     * 生成指定长度的重复字符字符串
     * 用于构建位图更新所需的匹配和替换字符串
     *
     * @param c 重复的字符
     * @param len 重复次数
     * @return 重复字符组成的字符串
     */
    private String fillString(char c, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}