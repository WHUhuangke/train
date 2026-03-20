package com.example.mapper;

import com.example.dto.StationOptionDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TrainStationMapper {

    @Select("SELECT DISTINCT station_id AS stationId, station_name AS stationName FROM t_train_station ORDER BY station_id")
    List<StationOptionDTO> selectStationOptions();
}
