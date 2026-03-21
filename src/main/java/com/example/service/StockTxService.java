package com.example.service;

import com.example.mapper.MessageLogMapper;
import com.example.mapper.TrainTicketStockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class StockTxService {

    @Autowired
    private TrainTicketStockMapper stockMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Transactional(rollbackFor = Exception.class)
    public void handleStockUpdateTx(Map<String, Object> msg) {
        String messageId = (String) msg.get("messageId");

        // 1. 兼容旧消息
        if (messageId == null) {
            log.warn("收到无messageId的旧消息，跳过业务处理: {}", msg);
            return;
        }

        // 2. 业务失败消息，直接忽略
        Object successObj = msg.get("success");
        boolean success = successObj instanceof Boolean
                ? (Boolean) successObj
                : Boolean.parseBoolean(String.valueOf(successObj));

        if (!success) {
            log.info("消息标记为失败，不处理库存: {}", messageId);
            return;
        }

        // 3. 幂等检查
        Integer status = messageLogMapper.getStatus(messageId);
        if (status != null && status == 2) {
            log.info("消息已处理，直接跳过: {}", messageId);
            return;
        }

        Long trainId = Long.parseLong(String.valueOf(msg.get("trainId")));
        Long seatId = Long.parseLong(String.valueOf(msg.get("seatId"))); // 当前没用到，可保留
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

        // 4. 扣减受影响区间库存
        stockMapper.decreaseAffectedStocks(trainId, sellStart, sellEnd, startIndex, endIndex, seatType);

        // 5. 删除缓存
        String pattern = "ticket:stock:*:" + trainId + ":" + seatType + ":*";
        Set<String> cacheKeys = redisTemplate.keys(pattern);
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redisTemplate.delete(cacheKeys);
        }

        // 6. 更新消息状态为已处理
        messageLogMapper.updateStatus(messageId, 2);

        log.info("事务处理完成: {}", messageId);
    }
}