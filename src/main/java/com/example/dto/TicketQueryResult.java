package com.example.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TicketQueryResult {
    private Long trainId;
    private String trainNumber; // 假设经由关联查询或缓存获取，如 G101
    private Integer fromStationId;
    private String fromStationName;
    private Integer toStationId;
    private String toStationName;
    private Integer stock;      // 余票数量
    private String departureTime;
    private String arrivalTime;
}