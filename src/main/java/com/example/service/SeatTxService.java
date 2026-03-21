package com.example.service;

import com.example.dto.SeatOrderRangeDTO;
import com.example.mapper.MessageLogMapper;
import com.example.mapper.TrainSeatMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SeatTxService {

    @Autowired
    private TrainSeatMapper seatMapper;
    @Autowired
    private OrderService orderService;
    @Autowired
    private MessageLogMapper messageLogMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    @Transactional(rollbackFor = Exception.class)
    public void handleBookingTx(Map<String, Object> msg) throws Exception {
        Long orderId = Long.valueOf(String.valueOf(msg.get("orderId")));
        Long trainId = Long.valueOf(String.valueOf(msg.get("trainId")));
        Long seatId = Long.valueOf(String.valueOf(msg.get("seatId")));
        Integer seatType = Integer.valueOf(String.valueOf(msg.get("seatType")));
        int sellStart = ((Number) msg.get("sellStart")).intValue();
        int sellEnd = ((Number) msg.get("sellEnd")).intValue();

        if (!orderService.isOrderPending(orderId)) {
            log.warn("订单已处理或不存在，跳过: orderId={}", orderId);
            return;
        }

        RLock lock = redissonClient.getLock("lock:seat:" + seatId);
        boolean locked = lock.tryLock(100, 10, TimeUnit.MILLISECONDS);
        if (!locked) {
            throw new IllegalStateException("获取座位锁失败, seatId=" + seatId);
        }

        try {
            int length = sellEnd - sellStart;
            String requiredZeros = fillString('0', length);
            String replacementOnes = fillString('1', length);

            int updated = seatMapper.updateSeatBitmapAtomic(
                
                    seatId,
                    sellStart + 1,
                    length,
                    requiredZeros,
                    replacementOnes
            );

            if (updated > 0) {
                String snapshotBitmap = seatMapper.selectBitmapBySeatId(seatId);

                orderService.markOrderSuccess(orderId);

                String messageId = UUID.randomUUID().toString();
                msg.put("success", true);
                msg.put("messageId", messageId);
                msg.put("snapshotBitmap", snapshotBitmap);

                String content = objectMapper.writeValueAsString(msg);
                messageLogMapper.insert(messageId, content, 0, LocalDateTime.now());

                log.info("订票成功，写入本地消息表: orderId={}, messageId={}", orderId, messageId);

            } else {
                orderService.markOrderFailed(orderId);

                // 仅失败时对账：按该座位的有效订单重放 Redis bitmap
                reconcileRedisSeatByOrders(trainId, seatType, seatId);

                msg.put("success", false);
                log.warn("座位位图更新失败，已按订单重放 Redis: orderId={}, seatId={}", orderId, seatId);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 按该 seatId 下所有状态=1的订单，重放生成最新 bitmap，并刷入 Redis
     */
    private void reconcileRedisSeatByOrders(Long trainId, Integer seatType, Long seatId) {
        String redisSeatKey = "seat:" + trainId + ":" + seatType + ":" + seatId;

        try {
            // 这里你要确保这个长度和数据库位图长度一致
            String dbBitmap = seatMapper.selectBitmapBySeatId(seatId);
            if (dbBitmap == null || dbBitmap.isEmpty()) {
                redisTemplate.delete(redisSeatKey);
                log.warn("订单重放对账时发现数据库无 bitmap，删除 Redis key={}", redisSeatKey);
                return;
            }

            int bitmapLength = dbBitmap.length();
            char[] rebuilt = new char[bitmapLength];
            for (int i = 0; i < bitmapLength; i++) {
                rebuilt[i] = '0';
            }

            List<SeatOrderRangeDTO> orders = orderService.listActiveSeatOrders(seatId);

            for (SeatOrderRangeDTO order : orders) {
                int start = order.getSellStart();
                int end = order.getSellEnd();

                // 这里按你当前代码约定：sellStart / sellEnd 是左闭右开区间
                for (int i = start; i < end && i < bitmapLength; i++) {
                    if (i >= 0) {
                        rebuilt[i] = '1';
                    }
                }
            }

            String rebuiltBitmap = new String(rebuilt);
            redisTemplate.opsForValue().set(redisSeatKey, rebuiltBitmap);

            log.info("Redis 对账完成(订单重放): key={}, bitmap={}", redisSeatKey, rebuiltBitmap);

        } catch (Exception e) {
            log.error("Redis 对账失败(订单重放), seatId={}, key={}", seatId, redisSeatKey, e);
        }
    }

    private String fillString(char c, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}