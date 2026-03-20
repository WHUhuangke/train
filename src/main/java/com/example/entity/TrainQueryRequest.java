package com.example.entity;

import lombok.Data;

/**
 * 车次查询请求
 */
@Data
public class TrainQueryRequest {
    private Integer fromStationId;
    private Integer toStationId;
    private String departureDate; // 2026-03-08
}
