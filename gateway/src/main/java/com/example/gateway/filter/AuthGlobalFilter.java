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

    private static final String AUTH_HEADER = "Authorization";
    private static final String LEGACY_TOKEN_HEADER = "X-Auth-Token";

    private final List<String> skipPaths;
    private final JwtTokenService jwtTokenService;
    private final SsoSessionService ssoSessionService;

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
        if (shouldSkip(path)) {
            return chain.filter(exchange);
        }

        String token = extractToken(request);
        if (StringUtils.isBlank(token)) {
            return unauthorized(exchange, "missing token");
        }

        JwtTokenService.TokenClaims claims;
        try {
            claims = jwtTokenService.parseAccessToken(token);
        } catch (JwtException ex) {
            log.warn("鉴权失败: path={}, reason={}", path, ex.getMessage());
            return unauthorized(exchange, "invalid token");
        }

        return ssoSessionService.isCurrentSession(claims.getUserId(), claims.getSessionId())
                .flatMap(current -> {
                    if (!current) {
                        log.warn("鉴权失败: path={}, reason=session expired, userId={}", path, claims.getUserId());
                        return unauthorized(exchange, "session expired");
                    }
                    ServerHttpRequest mutatedRequest = request.mutate()
                            .header("X-User-Id", claims.getUserId())
                            .header("X-User-Name", StringUtils.defaultString(claims.getUsername(), claims.getUserId()))
                            .header("X-Token-Id", claims.getSessionId())
                            .build();
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
    }

    private String extractToken(ServerHttpRequest request) {
        String bearer = request.getHeaders().getFirst(AUTH_HEADER);
        if (StringUtils.startsWithIgnoreCase(bearer, "Bearer ")) {
            return StringUtils.substringAfter(bearer, "Bearer ").trim();
        }
        return request.getHeaders().getFirst(LEGACY_TOKEN_HEADER);
    }

    private boolean shouldSkip(String path) {
        return skipPaths.stream().map(String::trim).anyMatch(path::startsWith);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"unauthorized\",\"reason\":\""
                + reason + "\",\"timestamp\":\"" + Instant.now().toString() + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
