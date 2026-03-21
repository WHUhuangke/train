package com.example.service;

import com.example.entity.Order;
import com.example.mapper.OrderMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 订单服务实现。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final String ORDER_CACHE_KEY_PREFIX = "order:";
    private static final Duration ORDER_CACHE_TTL = Duration.ofHours(2);

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(Order order) {
        orderMapper.insertOrder(order);
        cacheOrder(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long orderId, int status) {
        orderMapper.updateStatus(orderId, status);

        Order order = orderMapper.selectById(orderId);
        if (order != null) {
            cacheOrder(order);
        } else {
            redisTemplate.delete(ORDER_CACHE_KEY_PREFIX + orderId);
        }
    }

    @Override
    public Order getById(Long orderId) {
        String key = ORDER_CACHE_KEY_PREFIX + orderId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isEmpty()) {
            try {
                return objectMapper.readValue(cached, Order.class);
            } catch (Exception e) {
                redisTemplate.delete(key);
            }
        }

        Order order = orderMapper.selectById(orderId);
        if (order != null) {
            cacheOrder(order);
        }
        return order;
    }

    @Override
    public boolean isOrderPending(Long orderId) {
        Order order = getById(orderId);
        return order != null && Integer.valueOf(1).equals(order.getStatus());
    }

    @Override
    public void markOrderSuccess(Long orderId) {
        updateStatus(orderId, 2);
    }

    @Override
    public void markOrderFailed(Long orderId) {
        updateStatus(orderId, -1);
    }

    private void cacheOrder(Order order) {
        try {
            String key = ORDER_CACHE_KEY_PREFIX + order.getId();
            String value = objectMapper.writeValueAsString(order);
            redisTemplate.opsForValue().set(key, value, ORDER_CACHE_TTL);
        } catch (JsonProcessingException e) {
            // 缓存失败不影响主流程
        }
    }
}
