package com.example.service;

import com.example.entity.Order;
import com.example.mapper.MessageLogMapper;
import com.example.mapper.TrainStationMapper;
import com.example.mapper.TrainTicketStockMapper;
import com.example.entity.TrainStation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 Redis 的优化抢票服务。
 *
 * <p>核心目标：</p>
 * <ul>
 *     <li>通过 Redis Lua 保障座位位图修改的原子性。</li>
 *     <li>通过本地优先级队列保障「长区间优先」抢票策略。</li>
 *     <li>在用户确认购票时先对余票缓存执行预扣减，失败立即返回。</li>
 *     <li>当后续流程失败时执行缓存回滚与对账，避免缓存长期脏数据。</li>
 * </ul>
 */
@Service
public class OptimizedRedisTicketService {

    /** Redis 访问模板。 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 消息日志 Mapper（预留补偿使用）。 */
    @Autowired
    private MessageLogMapper messageLogMapper;

    /** MQ 生产者。 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** JSON 工具（预留扩展使用）。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 座位池初始化器。 */
    @Autowired
    private SeatPoolInitializer seatPoolInitializer;

    /** 订单服务。 */
    @Autowired
    private OrderService orderService;

    /** 站点数据访问（用于将站点 ID 解析为站序）。 */
    @Autowired
    private TrainStationMapper trainStationMapper;

    /** 余票数据访问（用于缓存对账）。 */
    @Autowired
    private TrainTicketStockMapper stockMapper;

    @Value("${train.rate-limit.capacity:100}")
    private int rateLimitCapacity;

    @Value("${train.rate-limit.rate:100}")
    private int rateLimitRate;

    @Value("${train.rate-limit.total-quota:2000}")
    private int totalQuota;

    /** 座位池懒加载锁，避免并发下重复初始化。 */
    private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();

    /** 每个车次+座位类型一个优先级队列（区间更长优先）。 */
    private final ConcurrentHashMap<String, PriorityBlockingQueue<BookingRequest>> bookingQueues = new ConcurrentHashMap<>();

    /** 每个车次+座位类型一个处理锁，确保同一队列仅有一个线程消费。 */
    private final ConcurrentHashMap<String, ReentrantLock> bookingQueueLocks = new ConcurrentHashMap<>();

    /** 用于为同优先级请求生成严格递增序号，保证稳定排序。 */
    private final AtomicLong requestSequence = new AtomicLong(0L);

    /** 座位缓存键前缀。 */
    private static final String STOCK_KEY_PREFIX = "ticket:stock:";

    /** 预扣减 Lua：批量扣减缓存；如果任一 key 不足，则自动回滚前面已扣减的 key。 */
    private static final String PRE_DEDUCT_STOCK_SCRIPT =
            "local deducted = {}\n" +
                    "for i = 1, #KEYS do\n" +
                    "  local val = redis.call('GET', KEYS[i])\n" +
                    "  if val then\n" +
                    "    local num = tonumber(val)\n" +
                    "    if (not num) or num <= 0 then\n" +
                    "      for j = 1, #deducted do\n" +
                    "        redis.call('INCRBY', deducted[j], 1)\n" +
                    "      end\n" +
                    "      return -1\n" +
                    "    end\n" +
                    "    redis.call('DECRBY', KEYS[i], 1)\n" +
                    "    table.insert(deducted, KEYS[i])\n" +
                    "  end\n" +
                    "end\n" +
                    "return #deducted";

    /** 预扣减回滚 Lua：将所有缓存 key +1。 */
    private static final String ROLLBACK_STOCK_SCRIPT =
            "for i = 1, #KEYS do\n" +
                    "  if redis.call('EXISTS', KEYS[i]) == 1 then\n" +
                    "    redis.call('INCRBY', KEYS[i], 1)\n" +
                    "  end\n" +
                    "end\n" +
                    "return #KEYS";

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
     * Redis Lua脚本：原子化座位分配。
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
     * Redis Lua脚本：订单落库失败时回滚座位占用。
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
     * 抢票入口：先排队，再按优先级（长区间优先）串行处理。
     */
    public String bookTicket(Long userId, Long trainId, int sellStart, int sellEnd, Integer seatType) {
        ensureSeatCacheLoaded(trainId, seatType);

        BookingRequest request = new BookingRequest(
                userId,
                trainId,
                sellStart,
                sellEnd,
                seatType,
                requestSequence.incrementAndGet()
        );

        String queueKey = buildQueueKey(trainId, seatType);
        bookingQueues.computeIfAbsent(queueKey, k -> new PriorityBlockingQueue<>()).offer(request);

        // 尝试触发队列消费；若已有线程在消费，当前线程只需等待结果。
        processQueue(queueKey);

        try {
            // 同步接口：等待本次请求处理完成。
            return request.resultFuture.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("抢票请求排队超时，请稍后重试", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("抢票请求处理失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("抢票线程被中断", e);
        }
    }

    /**
     * 消费一个队列中的请求。
     *
     * <p>采用 tryLock 避免并发线程重复消费同一个队列。</p>
     */
    private void processQueue(String queueKey) {
        ReentrantLock lock = bookingQueueLocks.computeIfAbsent(queueKey, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return;
        }

        try {
            PriorityBlockingQueue<BookingRequest> queue = bookingQueues.get(queueKey);
            if (queue == null) {
                return;
            }

            BookingRequest request;
            while ((request = queue.poll()) != null) {
                try {
                    String orderId = doBookTicket(request);
                    request.resultFuture.complete(orderId);
                } catch (Exception e) {
                    request.resultFuture.completeExceptionally(e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 真正执行抢票核心逻辑：
     * 1) 余票缓存预扣减
     * 2) 座位位图占用
     * 3) 订单落库
     * 4) MQ 通知
     */
    private String doBookTicket(BookingRequest request) {
        List<String> affectedStockKeys = listAffectedStockCacheKeys(
                request.trainId,
                request.sellStart,
                request.sellEnd,
                request.seatType
        );

        boolean stockPreDeducted = preDeductStock(affectedStockKeys);
        if (!stockPreDeducted) {
            throw new RuntimeException("余票预扣减失败，请稍后重试");
        }

        String seatId = tryAllocateSeat(request.trainId, request.seatType, request.sellStart, request.sellEnd);
        if (seatId == null) {
            rollbackPreDeductedStock(affectedStockKeys);
            // reconcileStockCache(affectedStockKeys);
            return null;
        }

        long orderId = nextId();
        Order order = Order.builder()
                .id(orderId)
                .userId(request.userId)
                .trainId(request.trainId)
                .seatId(Long.parseLong(seatId))
                .fromStationIndex(request.sellStart)
                .toStationIndex(request.sellEnd)
                .status(1)
                .build();

        try {
            orderService.createOrder(order);
        } catch (Exception e) {
            rollbackSeat(request.trainId, request.seatType, seatId, request.sellStart, request.sellEnd);
            rollbackPreDeductedStock(affectedStockKeys);
            // reconcileStockCache(affectedStockKeys);
            throw new RuntimeException("创建订单失败，已回滚座位与余票缓存", e);
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("orderId", orderId);
        msg.put("trainId", request.trainId);
        msg.put("seatId", Long.parseLong(seatId));
        msg.put("sellStart", request.sellStart);
        msg.put("sellEnd", request.sellEnd);
        msg.put("seatType", request.seatType);

        try {
            rabbitTemplate.convertAndSend("ticket.exchange", "ticket.book", msg);
        } catch (Exception e) {
            // 主流程不回滚订单，依赖补偿任务处理 MQ 异常。
        }

        return String.valueOf(orderId);
    }

    /**
     * 执行余票缓存预扣减。
     */
    private boolean preDeductStock(List<String> stockKeys) {
        if (stockKeys == null || stockKeys.isEmpty()) {
            return true;
        }
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(PRE_DEDUCT_STOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, stockKeys);
        return result != null && result >= 0;
    }

    /**
     * 将此前预扣减成功的缓存执行回滚。
     */
    private void rollbackPreDeductedStock(List<String> stockKeys) {
        if (stockKeys == null || stockKeys.isEmpty()) {
            return;
        }
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(ROLLBACK_STOCK_SCRIPT, Long.class);
        redisTemplate.execute(script, stockKeys);
    }

    /**
     * 余票缓存对账：从 DB 读取正确值并覆盖 Redis。
     */
    private void reconcileStockCache(List<String> stockKeys) {
        if (stockKeys == null || stockKeys.isEmpty()) {
            return;
        }
        for (String stockKey : stockKeys) {
            StockKeyParts parts = parseStockKey(stockKey);
            if (parts == null) {
                continue;
            }
            Integer dbStock = stockMapper.selectStockByUnique(
                    parts.trainId,
                    parts.seatType,
                    parts.fromStationId,
                    parts.toStationId
            );
            if (dbStock == null) {
                redisTemplate.delete(stockKey);
            } else {
                redisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
            }
        }
    }

    /**
     * 查询与当前购票区间有交集的余票缓存 key。
     */
    private List<String> listAffectedStockCacheKeys(Long trainId, int sellStart, int sellEnd, Integer seatType) {
        String pattern = STOCK_KEY_PREFIX + "*:" + trainId + ":" + seatType + ":*";
        Set<String> allKeys = redisTemplate.keys(pattern);
        if (allKeys == null || allKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<TrainStation> stations = trainStationMapper.selectByTrainId(trainId);
        if (stations == null || stations.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Integer, Integer> stationSeqMap = new HashMap<>();
        for (TrainStation station : stations) {
            stationSeqMap.put(station.getStationId(), station.getSequence());
        }

        List<String> affected = new ArrayList<>();
        for (String key : allKeys) {
            StockKeyParts parts = parseStockKey(key);
            if (parts == null) {
                continue;
            }
            Integer fromSeq = stationSeqMap.get(parts.fromStationId);
            Integer toSeq = stationSeqMap.get(parts.toStationId);
            if (fromSeq == null || toSeq == null) {
                continue;
            }
            boolean overlap = !(toSeq <= sellStart || fromSeq >= sellEnd);
            if (overlap) {
                affected.add(key);
            }
        }
        return affected;
    }

    /**
     * 解析缓存 key：ticket:stock:{date}:{trainId}:{seatType}:{fromStationId}:{toStationId}
     */
    private StockKeyParts parseStockKey(String key) {
        String[] arr = key.split(":");
        if (arr.length < 7) {
            return null;
        }
        try {
            long trainId = Long.parseLong(arr[3]);
            int seatType = Integer.parseInt(arr[4]);
            int fromStationId = Integer.parseInt(arr[5]);
            int toStationId = Integer.parseInt(arr[6]);
            return new StockKeyParts(trainId, seatType, fromStationId, toStationId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 尝试从 Redis 座位池分配座位。
     */
    private String tryAllocateSeat(Long trainId, Integer seatType, int sellStart, int sellEnd) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>(OPTIMIZED_SEAT_SCRIPT, String.class);
        return redisTemplate.execute(
                script,
                Collections.emptyList(),
                trainId.toString(),
                seatType.toString(),
                String.valueOf(sellStart),
                String.valueOf(sellEnd)
        );
    }

    /**
     * DB失败时回滚Redis座位占用。
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
     * 确保座位缓存已加载。
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

    /** 组合优先级队列 key。 */
    private String buildQueueKey(Long trainId, Integer seatType) {
        return trainId + ":" + seatType;
    }

    /** 优先级请求模型：区间长度越长，优先级越高。 */
    private static class BookingRequest implements Comparable<BookingRequest> {
        private final Long userId;
        private final Long trainId;
        private final int sellStart;
        private final int sellEnd;
        private final Integer seatType;
        private final long sequenceNo;
        private final CompletableFuture<String> resultFuture = new CompletableFuture<>();

        private BookingRequest(Long userId, Long trainId, int sellStart, int sellEnd, Integer seatType, long sequenceNo) {
            this.userId = userId;
            this.trainId = trainId;
            this.sellStart = sellStart;
            this.sellEnd = sellEnd;
            this.seatType = seatType;
            this.sequenceNo = sequenceNo;
        }

        private int tripLength() {
            return sellEnd - sellStart;
        }

        @Override
        public int compareTo(BookingRequest other) {
            int cmp = Integer.compare(other.tripLength(), this.tripLength());
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(this.sequenceNo, other.sequenceNo);
        }
    }

    /** 余票缓存 key 解析结果。 */
    private static class StockKeyParts {
        private final Long trainId;
        private final Integer seatType;
        private final Integer fromStationId;
        private final Integer toStationId;

        private StockKeyParts(Long trainId, Integer seatType, Integer fromStationId, Integer toStationId) {
            this.trainId = trainId;
            this.seatType = seatType;
            this.fromStationId = fromStationId;
            this.toStationId = toStationId;
        }
    }
}
