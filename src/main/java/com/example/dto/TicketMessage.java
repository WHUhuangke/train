package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketMessage implements Serializable {
    private String messageId; // UUID
    private Long orderId;
    private Long userId;
    private Long trainId;
    private Long seatId;
    private Integer seatType;
    private int sellStart;
    private int sellEnd;
    private int startIndex; // 周围最左连续0起始
    private int endIndex;   // 周围最右连续0结束
    private String reqZeros;
    private String repOnes;
}

