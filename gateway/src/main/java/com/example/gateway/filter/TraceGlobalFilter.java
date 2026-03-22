package com.example.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
public class TraceGlobalFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();

        long startTime = System.currentTimeMillis();
        String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signalType -> {
                    long cost = System.currentTimeMillis() - startTime;
                    log.info("traceId={}, method={}, path={}, status={}, cost={}ms",
                            finalTraceId,
                            request.getMethodValue(),
                            request.getURI().getPath(),
                            exchange.getResponse().getStatusCode(),
                            cost);
                });
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
