package com.example.service;

import com.example.dto.TicketAvailabilityDTO;
import com.example.entity.TrainTicketStock;
import com.example.mapper.TrainTicketStockMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class RestTicketService {

    // Redis缓存过期时间（30分钟）
    private static final long STOCK_CACHE_TTL_MINUTES = 30L;
    // 空库存标记缓存时间（2分钟，降低缓存穿透压力）
    private static final long EMPTY_STOCK_CACHE_TTL_MINUTES = 2L;
    // 空库存标记值
    private static final String EMPTY_STOCK_MARKER = "-1";

    /**
     * 请求合并 Future：同一库存 key 的并发缓存未命中只允许一个线程回源，其余线程复用结果。
     */
    private final ConcurrentHashMap<String, CompletableFuture<Integer>> stockLoadFutures = new ConcurrentHashMap<>();

    @Autowired
    private TrainTicketStockMapper stockMapper; // 数据库访问层

    @Autowired
    private StringRedisTemplate redisTemplate; // Redis操作模板

    /**
     * 查询某一天、某区间（from -> to）的余票情况
     *
     * @param departureDate 出发日期
     * @param fromId 出发站ID
     * @param toId 到达站ID
     * @return 每趟列车的余票情况列表
     */
    public List<TicketAvailabilityDTO> queryTicketStock(String departureDate, Integer fromId, Integer toId) {

        // 从数据库查询该区间所有车次 + 座位类型的库存记录
        List<TrainTicketStock> rows = stockMapper.selectStocksByRoute(fromId, toId);

        // 如果没有数据，直接返回空
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        // 用于按“车次+区间”聚合（一个DTO对应一趟车一个区间）
        Map<String, TicketAvailabilityDTO> trainRouteMap = new LinkedHashMap<>();

        for (TrainTicketStock row : rows) {

            // 唯一key：车次 + 出发站 + 到达站
            String routeKey = row.getTrainId() + ":" + row.getFromStationId() + ":" + row.getToStationId();

            // 如果不存在就创建DTO（聚合对象）
            TicketAvailabilityDTO dto = trainRouteMap.computeIfAbsent(routeKey, key ->
                    TicketAvailabilityDTO.builder()
                            .trainId(row.getTrainId())
                            .fromStationId(row.getFromStationId())
                            .fromIndex(row.getFromIndex())
                            .toStationId(row.getToStationId())
                            .toIndex(row.getToIndex())
                            .firstClassStock(0)   // 初始化一等座库存
                            .secondClassStock(0)  // 初始化二等座库存
                            .build()
            );

            // 从 Redis 或 DB 获取库存
            int stock = getStockWithRedisCache(departureDate, row);

            // 根据座位类型填充库存
            if (Integer.valueOf(1).equals(row.getSeatType())) {
                dto.setFirstClassStock(stock); // 一等座
            } else if (Integer.valueOf(2).equals(row.getSeatType())) {
                dto.setSecondClassStock(stock); // 二等座
            }
        }

        // 返回聚合结果
        return new ArrayList<>(trainRouteMap.values());
    }

    /**
     * 从 Redis 获取库存，如果没有则从数据库读取并写入缓存
     */
    private int getStockWithRedisCache(String departureDate, TrainTicketStock row) {

        // 构建缓存key
        String key = buildStockKey(
                departureDate,
                row.getTrainId(),
                row.getSeatType(),
                row.getFromStationId(),
                row.getToStationId()
        );

        // 先查Redis
        String cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            // 命中缓存，直接返回；空标记统一映射为 0，避免击穿 DB
            return EMPTY_STOCK_MARKER.equals(cached) ? 0 : Integer.parseInt(cached);
        }

        // 使用 Future 模式进行请求合并：并发 miss 时仅一个线程写缓存
        CompletableFuture<Integer> newFuture = new CompletableFuture<>();
        CompletableFuture<Integer> loadingFuture = stockLoadFutures.putIfAbsent(key, newFuture);
        if (loadingFuture == null) {
            try {
                int dbStock = Optional.ofNullable(row.getStock()).orElse(0);
                if (dbStock <= 0) {
                    redisTemplate.opsForValue().set(
                            key,
                            EMPTY_STOCK_MARKER,
                            EMPTY_STOCK_CACHE_TTL_MINUTES,
                            TimeUnit.MINUTES
                    );
                } else {
                    redisTemplate.opsForValue().set(
                            key,
                            String.valueOf(dbStock),
                            STOCK_CACHE_TTL_MINUTES,
                            TimeUnit.MINUTES
                    );
                }
                newFuture.complete(dbStock);
                return dbStock;
            } catch (Exception ex) {
                newFuture.completeExceptionally(ex);
                throw ex;
            } finally {
                stockLoadFutures.remove(key, newFuture);
            }
        }

        try {
            return loadingFuture.get(300, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            throw new RuntimeException("余票请求合并等待失败", ex.getCause());
        } catch (Exception ex) {
            throw new RuntimeException("余票请求合并等待超时", ex);
        }
    }

    /**
     * 构建库存缓存key
     *
     * key结构：
     * ticket:stock:日期:车次ID:座位类型:出发站:到达站
     *
     * 示例：
     * ticket:stock:2026-03-20:1001:1:1:5
     */
    public String buildStockKey(String departureDate,
                                Long trainId,
                                Integer seatType,
                                Integer fromStationId,
                                Integer toStationId) {

        return "ticket:stock:"
                + departureDate + ":"
                + trainId + ":"
                + seatType + ":"
                + fromStationId + ":"
                + toStationId;
    }
}
