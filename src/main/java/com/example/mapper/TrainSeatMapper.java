package com.example.mapper;

import com.example.entity.TrainSeat;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TrainSeatMapper {

    @Select("SELECT * FROM t_train_seat WHERE train_id = #{trainId} AND seat_type = #{seatType}")
    List<TrainSeat> selectByTrainId(@Param("trainId") Long trainId, @Param("seatType") Integer seatType);

    /**
     * 对指定 seat 行加锁
     * 事务提交前，其他事务不能修改这条记录
     */
    @Select("SELECT * FROM t_train_seat WHERE id = #{seatId} FOR UPDATE")
    TrainSeat selectByIdForUpdate(@Param("seatId") Long seatId);

    /**
     * 读取当前 seat 的 bitmap 快照
     */
    @Select("SELECT seat_bitmap FROM t_train_seat WHERE id = #{seatId}")
    String selectBitmapBySeatId(@Param("seatId") Long seatId);

    /**
     * 原子更新 bitmap
     */
    @Update("UPDATE t_train_seat SET seat_bitmap = CONCAT(" +
            "SUBSTRING(seat_bitmap, 1, #{startPos} - 1), " +
            "#{replacement}, " +
            "SUBSTRING(seat_bitmap, #{startPos} + #{length})) " +
            "WHERE id = #{seatId} " +
            "AND SUBSTRING(seat_bitmap, #{startPos}, #{length}) = #{required}")
    int updateSeatBitmapAtomic(@Param("seatId") Long seatId,
                               @Param("startPos") int startPos,
                               @Param("length") int length,
                               @Param("required") String required,
                               @Param("replacement") String replacement);
}