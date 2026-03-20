package com.example.service;

import com.example.dto.TicketAvailabilityDTO;
import com.example.entity.TrainTicketStock;
import com.example.mapper.TrainTicketStockMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class RestTicketService {

    private static final long STOCK_CACHE_TTL_MINUTES = 30L;

    @Autowired
    private TrainTicketStockMapper stockMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public List<TicketAvailabilityDTO> queryTicketStock(String departureDate, Integer fromId, Integer toId) {
        List<TrainTicketStock> rows = stockMapper.selectStocksByRoute(fromId, toId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, TicketAvailabilityDTO> trainRouteMap = new LinkedHashMap<>();
        for (TrainTicketStock row : rows) {
            String routeKey = row.getTrainId() + ":" + row.getFromStationId() + ":" + row.getToStationId();
            TicketAvailabilityDTO dto = trainRouteMap.computeIfAbsent(routeKey, key -> TicketAvailabilityDTO.builder()
                    .trainId(row.getTrainId())
                    .fromStationId(row.getFromStationId())
                    .fromIndex(row.getFromIndex())
                    .toStationId(row.getToStationId())
                    .toIndex(row.getToIndex())
                    .firstClassStock(0)
                    .secondClassStock(0)
                    .build());

            int stock = getStockWithRedisCache(departureDate, row);
            if (Integer.valueOf(1).equals(row.getSeatType())) {
                dto.setFirstClassStock(stock);
            } else if (Integer.valueOf(2).equals(row.getSeatType())) {
                dto.setSecondClassStock(stock);
            }
        }

        return new ArrayList<>(trainRouteMap.values());
    }

    private int getStockWithRedisCache(String departureDate, TrainTicketStock row) {
        String key = buildStockKey(departureDate, row.getTrainId(), row.getSeatType(), row.getFromStationId(), row.getToStationId());
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return Integer.parseInt(cached);
        }

        int dbStock = Optional.ofNullable(row.getStock()).orElse(0);
        redisTemplate.opsForValue().set(key, String.valueOf(dbStock), STOCK_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        return dbStock;
    }

    public String buildStockKey(String departureDate, Long trainId, Integer seatType, Integer fromStationId, Integer toStationId) {
        return "ticket:stock:" + departureDate + ":" + trainId + ":" + seatType + ":" + fromStationId + ":" + toStationId;
    }
}
