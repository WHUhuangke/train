package com.example.service;

import com.example.entity.Order;
import com.example.mapper.MessageLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于Redis的优化车票服务类
 * 核心功能：车票预订、座位分配、订单生成、消息队列通知
 * 优化特性：内置雪花算法ID生成、Redis Lua脚本原子操作、并发控制
 */
@Service
public class OptimizedRedisTicketService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SeatPoolInitializer seatPoolInitializer;

    @Autowired
    private OrderService orderService;

    @Value("${train.rate-limit.capacity:100}")
    private int rateLimitCapacity;

    @Value("${train.rate-limit.rate:100}")
    private int rateLimitRate;

    @Value("${train.rate-limit.total-quota:2000}")
    private int totalQuota;

    private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();

    // ---------- 内置雪花算法ID生成器 ----------
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private static final long WORKER_ID = 1L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards");
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                while (timestamp <= lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - 1288834974657L) << 22) | (WORKER_ID << 12) | sequence;
    }

    /**
     * Redis Lua脚本：原子化座位分配
     */
    private static final String OPTIMIZED_SEAT_SCRIPT =
            "local seatId = redis.call('LPOP', 'seat:pool:' .. ARGV[1] .. ':' .. ARGV[2])\n" +
                    "if not seatId then return nil end\n" +
                    "local seatKey = 'seat:' .. ARGV[1] .. ':' .. ARGV[2] .. ':' .. seatId\n" +
                    "local bitmap = redis.call('GET', seatKey)\n" +
                    "if not bitmap then return nil end\n" +
                    "local s, e = tonumber(ARGV[3]) + 1, tonumber(ARGV[4])\n" +
                    "local seg = bitmap:sub(s, e)\n" +
                    "if seg:find('1') then\n" +
                    "  redis.call('RPUSH', 'seat:pool:' .. ARGV[1] .. ':' .. ARGV[2], seatId)\n" +
                    "  return nil\n" +
                    "end\n" +
                    "redis.call('SET', seatKey, bitmap:sub(1, s - 1) .. ('1'):rep(e - s + 1) .. bitmap:sub(e + 1))\n" +
                    "return seatId";

    /**
     * Redis Lua脚本：订单落库失败时回滚座位占用
     * 功能：
     * 1. 将指定区间位图从 1 恢复为 0
     * 2. 将 seatId 重新放回 seat pool
     */
    private static final String ROLLBACK_SEAT_SCRIPT =
            "local seatKey = 'seat:' .. ARGV[1] .. ':' .. ARGV[2] .. ':' .. ARGV[3]\n" +
                    "local poolKey = 'seat:pool:' .. ARGV[1] .. ':' .. ARGV[2]\n" +
                    "local bitmap = redis.call('GET', seatKey)\n" +
                    "if not bitmap then return 0 end\n" +
                    "local s = tonumber(ARGV[4]) + 1\n" +
                    "local e = tonumber(ARGV[5])\n" +
                    "local restored = bitmap:sub(1, s - 1) .. ('0'):rep(e - s + 1) .. bitmap:sub(e + 1)\n" +
                    "redis.call('SET', seatKey, restored)\n" +
                    "redis.call('LPUSH', poolKey, ARGV[3])\n" +
                    "return 1";

    /**
     * 车票预订主方法
     */
    public String bookTicket(Long userId, Long trainId, int sellStart, int sellEnd, Integer seatType) {
        // 先确保座位缓存存在
        ensureSeatCacheLoaded(trainId, seatType);

        DefaultRedisScript<String> script = new DefaultRedisScript<>(OPTIMIZED_SEAT_SCRIPT, String.class);
        //从某个车厢/行程的可用座位池里取一个座位，并检查该座位某一段区间是否空闲，如果空闲就占用。
        String seatId = redisTemplate.execute(
                script,
                Collections.emptyList(),
                trainId.toString(),
                seatType.toString(),
                String.valueOf(sellStart),
                String.valueOf(sellEnd)
        );

        if (seatId == null) {
            return null;
        }

        long orderId = nextId();

        Order order = Order.builder()
                .id(orderId)
                .userId(userId)
                .trainId(trainId)
                .seatId(Long.parseLong(seatId))
                .fromStationIndex(sellStart)
                .toStationIndex(sellEnd)
                .status(1)
                .build();

        try {
            // 订单持久化 + Redis缓存
            orderService.createOrder(order);
        } catch (Exception e) {
            // 数据库写入失败，执行Redis座位回滚
            rollbackSeat(trainId, seatType, seatId, sellStart, sellEnd);
            throw new RuntimeException("创建订单失败，已回滚座位资源", e);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("orderId", orderId);
        msg.put("trainId", trainId);
        msg.put("seatId", Long.parseLong(seatId));
        msg.put("sellStart", sellStart);
        msg.put("sellEnd", sellEnd);
        msg.put("seatType", seatType);

        try {
            rabbitTemplate.convertAndSend("ticket.exchange", "ticket.book", msg);
        } catch (Exception e) {
            // 这里建议后续补 t_message_log 补偿
            // 主流程不回滚，避免用户下单成功却因为MQ异常被整体打回
        }

        return String.valueOf(orderId);
    }

    /**
     * DB失败时回滚Redis座位占用
     */
    private void rollbackSeat(Long trainId, Integer seatType, String seatId, int sellStart, int sellEnd) {
        DefaultRedisScript<Long> rollbackScript =
                new DefaultRedisScript<>(ROLLBACK_SEAT_SCRIPT, Long.class);

        redisTemplate.execute(
                rollbackScript,
                Collections.emptyList(),
                trainId.toString(),
                seatType.toString(),
                seatId,
                String.valueOf(sellStart),
                String.valueOf(sellEnd)
        );
    }

    /**
     * 确保座位缓存已加载
     */
    private void ensureSeatCacheLoaded(Long trainId, Integer seatType) {
        String poolKey = "seat:pool:" + trainId + ":" + seatType;
        Long poolSize = redisTemplate.opsForList().size(poolKey);

        if (poolSize == null || poolSize == 0) {
            String lockKey = trainId + ":" + seatType;
            Object lock = loadLocks.computeIfAbsent(lockKey, k -> new Object());

            synchronized (lock) {
                poolSize = redisTemplate.opsForList().size(poolKey);
                if (poolSize == null || poolSize == 0) {
                    seatPoolInitializer.initializeFromDatabase(trainId, seatType);
                }
            }
        }
    }

//    private boolean acquireToken(Long trainId) {
//        String key = "token:bucket:" + trainId;
//        DefaultRedisScript<Long> script = new DefaultRedisScript<>(TOKEN_BUCKET_WITH_QUOTA_SCRIPT, Long.class);
//        Long result = redisTemplate.execute(script, Collections.singletonList(key),
//                String.valueOf(rateLimitCapacity), String.valueOf(rateLimitRate),
//                String.valueOf(System.currentTimeMillis() / 1000), String.valueOf(totalQuota));
//        return result != null && result == 1;
//    }
}