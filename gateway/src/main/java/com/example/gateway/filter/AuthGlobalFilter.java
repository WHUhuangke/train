package com.example.gateway.filter;

import com.example.gateway.service.JwtTokenService;
import com.example.gateway.service.SsoSessionService;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    // 定义Token请求头常量
    private static final String AUTH_HEADER = "Authorization";    // 标准Bearer Token头
    private static final String LEGACY_TOKEN_HEADER = "X-Auth-Token"; // 旧系统兼容Token头

    // 白名单路径列表
    private final List<String> skipPaths;
    private final JwtTokenService jwtTokenService;    // JWT解析服务
    private final SsoSessionService ssoSessionService; // SSO会话验证服务

    // 构造函数，从配置加载白名单路径
    public AuthGlobalFilter(@Value("${gateway.auth.skip-paths:/actuator,/gateway/auth,/fallback}") String skipPathsText,
                            JwtTokenService jwtTokenService,
                            SsoSessionService ssoSessionService) {
        this.skipPaths = Arrays.asList(skipPathsText.split(","));
        this.jwtTokenService = jwtTokenService;
        this.ssoSessionService = ssoSessionService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        
        // 检查是否为白名单路径
        if (shouldSkip(path)) {
            return chain.filter(exchange); // 跳过鉴权
        }

        // 从请求头提取Token
        String token = extractToken(request);
        if (StringUtils.isBlank(token)) {
            return unauthorized(exchange, "missing token");
        }

        JwtTokenService.TokenClaims claims;
        try {
            // 解析JWT Token
            claims = jwtTokenService.parseAccessToken(token);
        } catch (JwtException ex) {
            log.warn("鉴权失败: path={}, reason={}", path, ex.getMessage());
            return unauthorized(exchange, "invalid token");
        }

        // 验证SSO会话是否有效（防止Token盗用或重复登录）
        return ssoSessionService.isCurrentSession(claims.getUserId(), claims.getSessionId())
                .flatMap(current -> {
                    if (!current) {
                        log.warn("鉴权失败: path={}, reason=session expired, userId={}", path, claims.getUserId());
                        return unauthorized(exchange, "session expired");
                    }
                    
                    // 鉴权通过，添加用户信息到请求头，传递给下游服务
                    ServerHttpRequest mutatedRequest = request.mutate()
                            .header("X-User-Id", claims.getUserId())         // 用户ID
                            .header("X-User-Name", StringUtils.defaultString(claims.getUsername(), claims.getUserId())) // 用户名
                            .header("X-Token-Id", claims.getSessionId())    // 会话ID
                            .build();
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
    }

    /**
     * 从请求头提取Token
     * 支持两种格式：
     * 1. 标准格式: Authorization: Bearer <token>
     * 2. 兼容格式: X-Auth-Token: <token>
     */
    private String extractToken(ServerHttpRequest request) {
        String bearer = request.getHeaders().getFirst(AUTH_HEADER);
        if (StringUtils.startsWithIgnoreCase(bearer, "Bearer ")) {
            return StringUtils.substringAfter(bearer, "Bearer ").trim();
        }
        return request.getHeaders().getFirst(LEGACY_TOKEN_HEADER);
    }

    /**
     * 判断路径是否需要跳过鉴权
     * 匹配规则：路径以白名单中的任意一个开头即可跳过
     */
    private boolean shouldSkip(String path) {
        return skipPaths.stream().map(String::trim).anyMatch(path::startsWith);
    }

    /**
     * 返回401未授权响应
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"unauthorized\",\"reason\":\""
                + reason + "\",\"timestamp\":\"" + Instant.now().toString() + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /**
     * 设置过滤器执行顺序（数字越小优先级越高）
     * 设置为-100确保在其他过滤器之前执行鉴权
     */
    @Override
    public int getOrder() {
        return -100;
    }
}