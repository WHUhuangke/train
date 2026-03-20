// TrainTicketManager.java - 车次管理工具
package com.example.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TrainTicketManager {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SeatPoolInitializer seatPoolInitializer;

    /**
     * 初始化单个车次的单个座位类型
     */
    public void initializeTrain(Long trainId, Integer seatType) {
        // 1. 从数据库加载座位配置到Redis
        seatPoolInitializer.initializeFromDatabase(trainId, seatType);

        // 2. 重置令牌桶
        String key = "token:bucket:" + trainId;
        redisTemplate.delete(key);
    }

    /**
     * 初始化单个车次的所有座位类型
     */
    public void initializeTrainAllTypes(Long trainId) {
        initializeTrain(trainId, 1); // 一等座
        initializeTrain(trainId, 2); // 二等座
    }

    /**
     * 批量初始化多个车次
     */
    public void initializeTrainRange(Long startTrainId, Long endTrainId) {
        for (long trainId = startTrainId; trainId <= endTrainId; trainId++) {
            try {
                initializeTrainAllTypes(trainId);
            } catch (Exception e) {
                // 记录日志，继续初始化下一个车次
            }
        }
    }

    /**
     * 查询剩余配额
     */
    public long getRemainingQuota(Long trainId, int totalQuota) {
        String key = "token:bucket:" + trainId;
        Object used = redisTemplate.opsForHash().get(key, "used");
        return totalQuota - (used != null ? Long.parseLong(used.toString()) : 0);
    }
}
