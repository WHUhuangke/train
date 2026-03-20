package com.example.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

import com.example.entity.TrainTicketStock;

@Mapper
public interface TrainTicketStockMapper {
    /**
     * 3. 查询固定区间的所有可能车票 (A站到B站)
     */
    @Select("SELECT * FROM t_train_ticket_stock " +
            "WHERE from_station_id = #{fId} AND to_station_id = #{tId} AND stock > 0")
    List<TrainTicketStock> selectStocksByRoute(@Param("fId") Integer fromStationId, @Param("tId") Integer toStationId);

    /**
     * 6. 扣减次数 (联动扣减所有受影响区间)
     * 算法要求：from >= startIndex && to <= endIndex && 与 [sellStart, sellEnd] 有交集
     * 交集判定：NOT (record.to_index <= sellStart OR record.from_index >= sellEnd)
     */
    @Update("UPDATE t_train_ticket_stock SET stock = stock - 1 " +
            "WHERE train_id = #{trainId} " +
            "AND from_index >= #{startIndex} " +
            "AND to_index <= #{endIndex} " +
            "AND NOT (to_index <= #{sellStart} OR from_index >= #{sellEnd}) " +
            "AND stock > 0 " +
            "AND seat_type = #{seatType}"
        )
    int decreaseAffectedStocks(@Param("trainId") Long trainId,
                               @Param("sellStart") int sellStart,
                               @Param("sellEnd") int sellEnd,
                               @Param("startIndex") int startIndex,
                               @Param("endIndex") int endIndex, Integer seatType);
}