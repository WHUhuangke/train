package com.example.service;

import com.example.entity.Order;

public interface OrderService {

    /**
     * 创建订单：落库 + 写Redis缓存
     */
    void createOrder(Order order);

    /**
     * 更新订单状态：落库 + 刷新Redis缓存
     */
    void updateStatus(Long orderId, int status);

    /**
     * 查询订单：优先查缓存，未命中查数据库并回填缓存
     */
    Order getById(Long orderId);
}