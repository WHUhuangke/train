package com.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 席位信息（对应 t_train_seat）
 * 核心：seatBitmap 记录各区间占用情况
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainSeat {
    private Long id;
    private Long trainId;
    private Integer carriageNum;
    private Integer rowNum;
    private String colNum;
    private Integer seatType; // 1:一等座, 2:二等座
    private String seatBitmap; // 位图字符串
}
