package com.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 车次余票统计（对应 t_train_ticket_stock）
 * 用于 A -> B 快速查询余票
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainTicketStock {
    private Long id;
    private Long trainId;
    private Integer fromStationId;
    private Integer fromIndex;
    private Integer toStationId;
    private Integer toIndex;
    private Integer stock;
}

