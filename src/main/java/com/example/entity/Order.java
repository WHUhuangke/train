package com.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单信息（对应 t_order）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private Long id;
    private Long userId;
    private Long trainId;
    private Long seatId;
    private Integer fromStationIndex;
    private Integer toStationIndex;
    private Integer status; // 0:待支付, 1:已支付, 2:已取消
    private LocalDateTime createTime;
}
