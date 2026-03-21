package com.example.service;

import com.example.entity.Order;

/**
 * 订单服务接口。
 */
public interface OrderService {

    /**
     * 创建订单：落库 + 写Redis缓存。
     */
    void createOrder(Order order);

    /**
     * 更新订单状态：落库 + 刷新Redis缓存。
     */
    void updateStatus(Long orderId, int status);

    /**
     * 查询订单：优先查缓存，未命中查数据库并回填缓存。
     */
    Order getById(Long orderId);

    /**
     * 订单是否处于待处理状态（status=1）。
     */
    boolean isOrderPending(Long orderId);

    /**
     * 标记订单成功（status=2）。
     */
    void markOrderSuccess(Long orderId);

    /**
     * 标记订单失败（status=-1）。
     */
    void markOrderFailed(Long orderId);
}
