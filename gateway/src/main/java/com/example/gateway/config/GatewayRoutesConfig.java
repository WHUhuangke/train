package com.example.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // 路线1：查询票务路由（只处理查询操作）
                .route("ticket_query_route", r -> r
                        // 匹配路径：/gateway/ticket/query
                        .path("/gateway/ticket/query")
                        // 只允许GET方法
                        .and()
                        .method(HttpMethod.GET)
                        .filters(f -> f
                                // 移除路径前缀（去掉"/gateway"）
                                .stripPrefix(1)
                                // 重试配置：失败时重试2次
                                .retry(config -> config
                                        .setRetries(2)
                                        .setMethods(HttpMethod.GET)
                                        .setStatuses(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR))
                                // 熔断器配置
                                .circuitBreaker(c -> c  
                                        .setName("ticket-query-cb")
                                        .setFallbackUri("forward:/fallback/ticket")))
                        // 转发到实际的票务服务
                        .uri("http://127.0.0.1:8080"))
                
                // 路线2：票务写操作路由（处理除查询外的所有票务操作）
                .route("ticket_write_route", r -> r
                        // 匹配路径：/gateway/ticket/下的所有请求
                        .path("/gateway/ticket/**")
                        .filters(f -> f
                                // 移除路径前缀
                                .stripPrefix(1)
                                // 请求限流配置
                                .requestRateLimiter(config -> {
                                    config.setRateLimiter(redisRateLimiter());
                                    // 基于用户ID进行限流
                                    config.setKeyResolver(exchange -> reactor.core.publisher.Mono.just(
                                            exchange.getRequest().getHeaders().getFirst("X-User-Id") == null
                                                    ? "anonymous"  // 匿名用户
                                                    : exchange.getRequest().getHeaders().getFirst("X-User-Id")));
                                })
                                // 熔断器配置
                                .circuitBreaker(c -> c
                                        .setName("ticket-write-cb")
                                        .setFallbackUri("forward:/fallback/common")))
                        // 转发到实际的票务服务
                        .uri("http://127.0.0.1:8080"))
                .build();
    }

    /**
     * Redis分布式限流器配置
     * 参数说明：
     * 1. replenishRate（令牌补充速率）：20个/秒
     * 2. burstCapacity（突发容量）：40个令牌
     * 3. requestedTokens（每次请求消耗令牌数）：1个
     * 
     * 解释：每秒钟最多处理20个请求，允许短时间内最多40个请求的突发流量
     */
    @Bean
    public org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter redisRateLimiter() {
        return new org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(20, 40, 1);
    }   
}