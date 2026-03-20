package com.example.mapper;

import com.example.dto.StationOptionDTO;
import com.example.entity.TrainStation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 车站信息 Mapper。
 */
@Mapper
public interface TrainStationMapper {

    /**
     * 查询全量站点选项（用于前端筛选）。
     */
    @Select("SELECT DISTINCT station_id AS stationId, station_name AS stationName FROM t_train_station ORDER BY station_id")
    List<StationOptionDTO> selectStationOptions();

    /**
     * 查询指定车次站点与站序。
     */
    @Select("SELECT station_id, sequence FROM t_train_station WHERE train_id = #{trainId}")
    List<TrainStation> selectByTrainId(Long trainId);
}
