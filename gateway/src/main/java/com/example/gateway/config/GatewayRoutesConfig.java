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
                .route("ticket_query_route", r -> r
                        .path("/gateway/ticket/query")
                        .and()
                        .method(HttpMethod.GET)
                        .filters(f -> f
                                .stripPrefix(1)
                                .retry(config -> config
                                        .setRetries(2)
                                        .setMethods(HttpMethod.GET)
                                        .setStatuses(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR))
                                .circuitBreaker(c -> c
                                        .setName("ticket-query-cb")
                                        .setFallbackUri("forward:/fallback/ticket")))
                        .uri("http://127.0.0.1:8080"))
                .route("ticket_write_route", r -> r
                        .path("/gateway/ticket/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .requestRateLimiter(config -> {
                                    config.setRateLimiter(redisRateLimiter());
                                    config.setKeyResolver(exchange -> reactor.core.publisher.Mono.just(
                                            exchange.getRequest().getHeaders().getFirst("X-User-Id") == null
                                                    ? "anonymous"
                                                    : exchange.getRequest().getHeaders().getFirst("X-User-Id")));
                                })
                                .circuitBreaker(c -> c
                                        .setName("ticket-write-cb")
                                        .setFallbackUri("forward:/fallback/common")))
                        .uri("http://127.0.0.1:8080"))
                .build();
    }

    @Bean
    public org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter redisRateLimiter() {
        return new org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(20, 40, 1);
    }
}
