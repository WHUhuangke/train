package com.example.service;

import com.example.dto.StockOrderRangeDTO;
import com.example.mapper.MessageLogMapper;
import com.example.mapper.TrainTicketStockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StockTxService {

    @Autowired
    private TrainTicketStockMapper stockMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private OrderService orderService;

    @Transactional(rollbackFor = Exception.class)
    public void handleStockUpdateTx(Map<String, Object> msg) {
        String messageId = (String) msg.get("messageId");

        if (messageId == null) {
            log.warn("收到无messageId的旧消息，跳过业务处理: {}", msg);
            return;
        }

        Object successObj = msg.get("success");
        boolean success = successObj instanceof Boolean
                ? (Boolean) successObj
                : Boolean.parseBoolean(String.valueOf(successObj));

        if (!success) {
            log.info("消息标记为失败，不处理库存: {}", messageId);
            return;
        }

        Integer status = messageLogMapper.getStatus(messageId);
        if (status != null && status == 2) {
            log.info("消息已处理，直接跳过: {}", messageId);
            return;
        }

        Long trainId = Long.parseLong(String.valueOf(msg.get("trainId")));
        int sellStart = ((Number) msg.get("sellStart")).intValue();
        int sellEnd = ((Number) msg.get("sellEnd")).intValue();
        int seatType = ((Number) msg.get("seatType")).intValue();
        String bitmap = String.valueOf(msg.get("snapshotBitmap"));

        int startIndex = 0;
        int endIndex = bitmap.length();

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

        // 1. 先更新数据库库存
        stockMapper.decreaseAffectedStocks(trainId, sellStart, sellEnd, startIndex, endIndex, seatType);

        // 2. 再按订单表 status=2 重放 Redis 余票缓存
        reconcileStockRedisByOrders(trainId, seatType);

        // 3. 更新消息状态为已处理
        messageLogMapper.updateStatus(messageId, 2);

        log.info("库存事务处理完成: {}", messageId);
    }

    /**
     * 按订单表中状态=2的订单，重放该 trainId + seatType 的余票缓存
     */
    private void reconcileStockRedisByOrders(Long trainId, Integer seatType) {
        try {
            // 1. 查询总库存基础信息
            Integer stationCount = stockMapper.selectStationCount(trainId);
            Integer totalSeats = stockMapper.selectTotalSeats(trainId, seatType);

            if (stationCount == null || stationCount <= 1 || totalSeats == null || totalSeats < 0) {
                log.warn("余票对账跳过，基础数据异常, trainId={}, seatType={}, stationCount={}, totalSeats={}",
                        trainId, seatType, stationCount, totalSeats);
                return;
            }

            // 2. 查询所有已生效订单（status=2）
            List<StockOrderRangeDTO> orders = orderService.listSuccessOrdersForStockReplay(trainId, seatType);

            // sold[i][j] 表示区间 [i, j) 已售数量
            int[][] sold = new int[stationCount][stationCount];

            for (StockOrderRangeDTO order : orders) {
                int orderStart = order.getSellStart();
                int orderEnd = order.getSellEnd();

                // 一个订单占用 [orderStart, orderEnd)，
                // 它会影响所有与其区间有重叠的查询区间 [i, j)
                for (int i = 0; i < stationCount; i++) {
                    for (int j = i + 1; j < stationCount; j++) {
                        if (hasOverlap(i, j, orderStart, orderEnd)) {
                            sold[i][j]++;
                        }
                    }
                }
            }

            // 3. 重建 Redis 余票缓存
            for (int i = 0; i < stationCount; i++) {
                for (int j = i + 1; j < stationCount; j++) {
                    int remain = totalSeats - sold[i][j];
                    if (remain < 0) {
                        remain = 0;
                    }

                    String redisKey = buildStockRedisKey(trainId, seatType, i, j);
                    redisTemplate.opsForValue().set(redisKey, String.valueOf(remain));
                }
            }

            log.info("余票 Redis 对账完成: trainId={}, seatType={}", trainId, seatType);

        } catch (Exception e) {
            log.error("余票 Redis 对账失败: trainId={}, seatType={}", trainId, seatType, e);
        }
    }

    /**
     * 判断两个左闭右开区间 [aStart, aEnd) 和 [bStart, bEnd) 是否重叠
     */
    private boolean hasOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    /**
     * 构造余票缓存 key
     * 这里你要按你们项目实际 key 规则改
     */
    private String buildStockRedisKey(Long trainId, Integer seatType, int start, int end) {
        return "ticket:stock:" + start + ":" + trainId + ":" + seatType + ":" + end;
    }
}