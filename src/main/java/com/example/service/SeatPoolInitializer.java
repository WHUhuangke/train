// SeatPoolInitializer.java - 从数据库动态加载
package com.example.service;

import com.example.entity.TrainSeat;
import com.example.mapper.TrainSeatMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SeatPoolInitializer {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private TrainSeatMapper trainSeatMapper;

    /**
     * 从数据库加载座位配置到Redis
     */
    public void initializeFromDatabase(Long trainId, Integer seatType) {
        // 1. 查询该车次该座位类型的所有座位
        List<TrainSeat> seats = trainSeatMapper.selectByTrainId(trainId, seatType);

        if (seats.isEmpty()) {
            throw new RuntimeException("车次" + trainId + "座位类型" + seatType + "无座位数据");
        }

        // 2. 初始化座位bitmap到Redis
        for (TrainSeat seat : seats) {
            String seatKey = "seat:" + trainId + ":" + seatType + ":" + seat.getId();
            redisTemplate.opsForValue().set(seatKey, seat.getSeatBitmap());
        }

        // 3. 初始化座位池（可用座位ID列表）
        String poolKey = "seat:pool:" + trainId + ":" + seatType;
        redisTemplate.delete(poolKey);
        List<String> seatIds = seats.stream().map(seat -> String.valueOf(seat.getId()))
                .collect(Collectors.toList());
        redisTemplate.opsForList().rightPushAll(poolKey, seatIds);
    }
}
