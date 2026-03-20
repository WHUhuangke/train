package com.example.service;

import com.example.entity.TrainTicketStock;
import com.example.mapper.TrainTicketStockMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class RestTicketService {

    @Autowired
    private TrainTicketStockMapper stockMapper;

    public List<TrainTicketStock> queryTicketStock(Integer fromId, Integer toId) {
        // 调用之前实现的 Task 3：查询固定区间所有可能车票
        return stockMapper.selectStocksByRoute(fromId, toId);
    }
}