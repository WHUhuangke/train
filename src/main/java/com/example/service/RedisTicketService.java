// OptimizedRedisTicketService.java
package com.example.service;

import com.example.entity.Order;
import com.example.mapper.OrderMapper;
import com.example.mapper.MessageLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class RedisTicketService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private MessageLogMapper messageLogMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    // 令牌桶获取脚本
    private static final String TOKEN_BUCKET_SCRIPT =
            "local key = KEYS[1]\n" +
                    "local capacity = tonumber(ARGV[1])\n" +
                    "local rate = tonumber(ARGV[2])\n" +
                    "local now = tonumber(ARGV[3])\n" +
                    "local tokens = tonumber(redis.call('HGET', key, 'tokens') or capacity)\n" +
                    "local lastTime = tonumber(redis.call('HGET', key, 'lastTime') or now)\n" +
                    "local delta = math.max(0, now - lastTime)\n" +
                    "local newTokens = math.min(capacity, tokens + delta * rate)\n" +
                    "if newTokens >=1 then\n" +
                    "  redis.call('HSET', key, 'tokens', newTokens - 1)\n" +
                    "  redis.call('HSET', key, 'lastTime', now)\n" +
                    "  redis.call('EXPIRE', key, 3600)\n" +
                    "  return 1\n" +
                    "end\n" +
                    "return 0";

    // 优化的座位查找和占用脚本
    private static final String OPTIMIZED_SEAT_SCRIPT =
            "local trainId = ARGV[1]\n" +
                    "local seatType = ARGV[2]\n" +
                    "local sellStart = tonumber(ARGV[3])\n" +
                    "local sellEnd = tonumber(ARGV[4])\n" +
                    "local poolKey = 'seat:pool:' .. trainId .. ':' .. seatType\n" +
                    "local seatId = redis.call('LPOP', poolKey)\n" +
                    "if not seatId then return nil end\n" +
                    "local seatKey = 'seat:' .. trainId .. ':' .. seatType .. ':' .. seatId\n" +
                    "local bitmap = redis.call('GET', seatKey)\n" +
                    "if bitmap then\n" +
                    "  local length = sellEnd - sellStart\n" +
                    "  local segment = string.sub(bitmap, sellStart + 1, sellEnd)\n" +
                    "  local requiredZeros = string.rep('0', length)\n" +
                    "  if segment == requiredZeros then\n" +
                    "    local replacementOnes = string.rep('1', length)\n" +
                    "    local newBitmap = string.sub(bitmap, 1, sellStart) .. replacementOnes .. string.sub(bitmap, sellEnd + 1)\n" +
                    "    redis.call('SET', seatKey, newBitmap)\n" +
                    "    return seatId\n" +
                    "  else\n" +
                    "    redis.call('RPUSH', poolKey, seatId)\n" +
                    "    return nil\n" +
                    "  end\n" +
                    "end\n" +
                    "return nil";

    @Transactional(rollbackFor = Exception.class)
    public String bookTicket(Long userId, Long trainId, int sellStart, int sellEnd, Integer seatType) {
        // 1. 令牌桶限流
        if (!acquireToken(trainId, 100, 10)) {
            throw new RuntimeException("系统繁忙，请稍后重试");
        }

        // 2. 优化的座位查找
        DefaultRedisScript<String> script = new DefaultRedisScript<>(OPTIMIZED_SEAT_SCRIPT, String.class);
        String seatId = redisTemplate.execute(script, Collections.emptyList(),
                trainId.toString(), seatType.toString(), String.valueOf(sellStart), String.valueOf(sellEnd));

        if (seatId == null) {
            return null;
        }

        // 3. 记录订单
        String orderId = UUID.randomUUID().toString();
        Order order = Order.builder()
                .id(Long.parseLong(orderId.replace("-", "").substring(0, 10), 16))
                .userId(userId)
                .trainId(trainId)
                .seatId(Long.parseLong(seatId))
                .fromStationIndex(sellStart)
                .toStationIndex(sellEnd)
                .status(1)
                .build();
        orderMapper.insertOrder(order);

        // 4. 发送MQ消息
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> msg = new HashMap<>();
        msg.put("orderId", order.getId());
        msg.put("trainId", trainId);
        msg.put("seatId", Long.parseLong(seatId));
        msg.put("sellStart", sellStart);
        msg.put("sellEnd", sellEnd);
        msg.put("seatType", seatType);

        try {
            String content = objectMapper.writeValueAsString(msg);
            messageLogMapper.insert(messageId, content, 0, LocalDateTime.now().plusMinutes(5));
            rabbitTemplate.convertAndSend("ticket.exchange", "ticket.book", msg);
            messageLogMapper.updateStatus(messageId, 1);
        } catch (Exception e) {
            // 消息发送失败，后续补偿
        }

        return orderId;
    }

    private boolean acquireToken(Long trainId, int capacity, int rate) {
        String key = "token:bucket:" + trainId;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(key),
                String.valueOf(capacity), String.valueOf(rate), String.valueOf(System.currentTimeMillis() / 1000));
        return result != null && result == 1;
    }
}
