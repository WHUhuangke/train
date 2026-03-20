package com.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 车次经停站（对应 t_train_station）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainStation {
    private Long id;
    private Long trainId;
    private Integer stationId;
    private String stationName;
    private Integer sequence;
    private LocalDateTime arrivalTime;
    private LocalDateTime departureTime;
}
