package com.example.service;

import com.example.entity.TrainStation;
import com.example.mapper.TrainStationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 业务规则校验器。
 *
 * <p>用于统一承接关键参数的合法性校验，避免将无效/恶意请求下沉到核心流程。</p>
 */
@Component
public class BusinessRuleValidator {

    @Autowired
    private TrainStationMapper trainStationMapper;

    /**
     * 校验购票请求参数。
     */
    public void validateBookRequest(Long userId, Long trainId, int fromIndex, int toIndex, Integer seatType) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 非法");
        }
        if (trainId == null || trainId <= 0) {
            throw new IllegalArgumentException("trainId 非法");
        }
        if (seatType == null || (seatType != 1 && seatType != 2)) {
            throw new IllegalArgumentException("seatType 非法，仅支持 1/2");
        }
        if (fromIndex < 0 || toIndex <= fromIndex) {
            throw new IllegalArgumentException("乘车区间非法");
        }

        List<TrainStation> stations = trainStationMapper.selectByTrainId(trainId);
        if (stations == null || stations.isEmpty()) {
            throw new IllegalArgumentException("trainId 不存在");
        }
        int maxStationIndex = stations.stream()
                .map(TrainStation::getSequence)
                .max(Integer::compareTo)
                .orElse(-1);
        if (toIndex > maxStationIndex) {
            throw new IllegalArgumentException("乘车区间超出车次站点范围");
        }
    }
}
