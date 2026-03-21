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

        // 更推荐改成原子抢占，而不是单纯 isOrderPending
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

            String snapshotBitmap = seatMapper.selectBitmapBySeatId(seatId);

            if (updated > 0) {
                orderService.markOrderSuccess(orderId);

                // 可选：缓存操作改到 afterCommit 或异步修复
                reconcileRedisSeat(trainId, seatType, seatId, snapshotBitmap);

                String messageId = UUID.randomUUID().toString();
                msg.put("success", true);
                msg.put("messageId", messageId);
                msg.put("snapshotBitmap", snapshotBitmap);

                String content = objectMapper.writeValueAsString(msg);
                messageLogMapper.insert(messageId, content, 0, LocalDateTime.now());

            } else {
                orderService.markOrderFailed(orderId);
                msg.put("success", false);
                msg.put("snapshotBitmap", snapshotBitmap);

                reconcileRedisSeat(trainId, seatType, seatId, snapshotBitmap);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void reconcileRedisSeat(Long trainId, Integer seatType, Long seatId, String dbBitmap) {
        String redisSeatKey = "seat:" + trainId + ":" + seatType + ":" + seatId;
        if (dbBitmap == null || dbBitmap.isEmpty()) {
            redisTemplate.delete(redisSeatKey);
        } else {
            redisTemplate.opsForValue().set(redisSeatKey, dbBitmap);
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