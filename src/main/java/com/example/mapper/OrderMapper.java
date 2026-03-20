// OrderMapper.java - 添加状态更新方法
package com.example.mapper;

import com.example.entity.Order;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrderMapper {

    @Insert("INSERT INTO t_order (id, user_id, train_id, seat_id, from_station_index, to_station_index, status) " +
            "VALUES (#{id}, #{userId}, #{trainId}, #{seatId}, #{fromStationIndex}, #{toStationIndex}, #{status})")
    void insertOrder(Order order);

    @Update("UPDATE t_order SET status = #{status} WHERE id = #{orderId}")
    void updateStatus(@Param("orderId") Long orderId, @Param("status") int status);

    @Select("SELECT * FROM t_order WHERE id = #{orderId}")
    Order selectById(@Param("orderId") Long orderId);
}
