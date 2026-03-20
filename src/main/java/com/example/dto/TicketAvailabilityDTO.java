package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketAvailabilityDTO {
    private Long trainId;
    private Integer fromStationId;
    private Integer fromIndex;
    private Integer toStationId;
    private Integer toIndex;
    private Integer firstClassStock;
    private Integer secondClassStock;
}
