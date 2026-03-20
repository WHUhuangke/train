package com.example.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

import com.example.entity.TrainTicketStock;

/**
 * 余票 Mapper。
 */
@Mapper
public interface TrainTicketStockMapper {
    /**
     * 查询固定区间的所有车次余票（含一等/二等）。
     */
    @Select("SELECT * FROM t_train_ticket_stock " +
            "WHERE from_station_id = #{fId} AND to_station_id = #{tId}")
    List<TrainTicketStock> selectStocksByRoute(@Param("fId") Integer fromStationId, @Param("tId") Integer toStationId);

    /**
     * 按唯一维度查询余票，用于 Redis 余票缓存对账。
     */
    @Select("SELECT stock FROM t_train_ticket_stock WHERE train_id = #{trainId} " +
            "AND seat_type = #{seatType} AND from_station_id = #{fromStationId} AND to_station_id = #{toStationId} LIMIT 1")
    Integer selectStockByUnique(@Param("trainId") Long trainId,
                                @Param("seatType") Integer seatType,
                                @Param("fromStationId") Integer fromStationId,
                                @Param("toStationId") Integer toStationId);

    /**
     * 扣减次数（联动扣减所有受影响区间）。
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
                               @Param("endIndex") int endIndex,
                               @Param("seatType") Integer seatType);
}
