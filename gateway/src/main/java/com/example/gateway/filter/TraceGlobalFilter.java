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

/**
 * 全局链路追踪过滤器
 * 为每个请求添加TraceId，并记录请求处理耗时
 */
@Slf4j
@Component
public class TraceGlobalFilter implements GlobalFilter, Ordered {

    // 定义TraceId在HTTP Header中的字段名
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 获取或生成TraceId
        // 先尝试从请求头中获取已有的TraceId
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        // 如果请求头中没有TraceId，则生成一个新的
        if (traceId == null || traceId.isEmpty()) {
            // 生成UUID并移除横线，作为TraceId
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 2. 将TraceId添加到请求头中
        // 使用mutate()方法创建请求的副本，并添加TraceId请求头
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, traceId)  // 添加TraceId到请求头
                .build();

        // 3. 记录请求开始时间
        long startTime = System.currentTimeMillis();
        
        // 4. 将TraceId声明为final，以便在lambda表达式中使用
        String finalTraceId = traceId;
        
        // 5. 继续过滤器链的执行
        // 将修改后的请求设置到exchange中，并继续执行后续过滤器
        return chain.filter(exchange.mutate().request(request).build())
                // 6. 在请求处理完成后记录日志
                .doFinally(signalType -> {
                    // 计算请求处理耗时
                    long cost = System.currentTimeMillis() - startTime;
                    // 记录详细的请求信息日志
                    log.info("traceId={}, method={}, path={}, status={}, cost={}ms",
                            finalTraceId,                       // 链路追踪ID
                            request.getMethodValue(),           // 请求方法（GET/POST等）
                            request.getURI().getPath(),         // 请求路径
                            exchange.getResponse().getStatusCode(), // 响应状态码
                            cost);                              // 请求耗时（毫秒）
                });
    }

    @Override
    public int getOrder() {
        // 设置过滤器的执行顺序，数值越小优先级越高
        // 设置为-200确保在大多数过滤器之前执行
        return -200;
    }
}