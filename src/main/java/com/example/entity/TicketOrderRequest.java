package com.example.entity;

import lombok.Data;

/**
 * 下单请求
 */
@Data
public class TicketOrderRequest {
    private Long userId;
    private Long trainId;
    private Integer fromStationIndex;
    private Integer toStationIndex;
    private Integer seatType; // 乘客选择的席别
}
