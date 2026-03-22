package com.example.gateway.controller;

import com.example.gateway.service.JwtTokenService;
import com.example.gateway.service.SsoSessionService;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/gateway/auth")
public class AuthController {

    private final JwtTokenService jwtTokenService;
    private final SsoSessionService ssoSessionService;

    public AuthController(JwtTokenService jwtTokenService, SsoSessionService ssoSessionService) {
        this.jwtTokenService = jwtTokenService;
        this.ssoSessionService = ssoSessionService;
    }

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody LoginRequest request) {
        if (StringUtils.isBlank(request.getUserId()) || StringUtils.isBlank(request.getPassword())) {
            return Mono.just(error(HttpStatus.BAD_REQUEST, "userId/password 不能为空"));
        }

        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(
                request.getUserId(),
                StringUtils.defaultIfBlank(request.getUsername(), request.getUserId())
        );
        Duration ttl = Duration.between(Instant.now(), tokenPair.getAccessExpireAt());

        return ssoSessionService.bindSession(request.getUserId(), tokenPair.getSessionId(), ttl)
                .map(ok -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", true);
                    body.put("tokenType", "Bearer");
                    body.put("accessToken", tokenPair.getAccessToken());
                    body.put("refreshToken", tokenPair.getRefreshToken());
                    body.put("accessExpireAt", tokenPair.getAccessExpireAt().toString());
                    body.put("refreshExpireAt", tokenPair.getRefreshExpireAt().toString());
                    body.put("userId", request.getUserId());
                    return ResponseEntity.ok(body);
                });
    }

    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> refresh(@RequestBody RefreshRequest request) {
        try {
            JwtTokenService.TokenClaims claims = jwtTokenService.parseRefreshToken(request.getRefreshToken());
            return ssoSessionService.isCurrentSession(claims.getUserId(), claims.getSessionId())
                    .flatMap(current -> {
                        if (!current) {
                            return Mono.just(error(HttpStatus.UNAUTHORIZED, "登录态已失效，请重新登录"));
                        }
                        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueTokenPair(claims.getUserId(), claims.getUsername());
                        Duration ttl = Duration.between(Instant.now(), tokenPair.getAccessExpireAt());
                        return ssoSessionService.bindSession(claims.getUserId(), tokenPair.getSessionId(), ttl)
                                .map(ok -> {
                                    Map<String, Object> body = new LinkedHashMap<>();
                                    body.put("success", true);
                                    body.put("tokenType", "Bearer");
                                    body.put("accessToken", tokenPair.getAccessToken());
                                    body.put("refreshToken", tokenPair.getRefreshToken());
                                    body.put("accessExpireAt", tokenPair.getAccessExpireAt().toString());
                                    body.put("refreshExpireAt", tokenPair.getRefreshExpireAt().toString());
                                    return ResponseEntity.ok(body);
                                });
                    });
        } catch (Exception ex) {
            return Mono.just(error(HttpStatus.UNAUTHORIZED, "refreshToken 无效或已过期"));
        }
    }

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> logout(@RequestBody LogoutRequest request) {
        if (StringUtils.isBlank(request.getUserId())) {
            return Mono.just(error(HttpStatus.BAD_REQUEST, "userId 不能为空"));
        }
        return ssoSessionService.clearSession(request.getUserId())
                .map(cleared -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("success", true);
                    body.put("message", cleared ? "已注销" : "登录态不存在");
                    return ResponseEntity.ok(body);
                });
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }

    @Data
    public static class LoginRequest {
        private String userId;
        private String username;
        private String password;
    }

    @Data
    public static class RefreshRequest {
        private String refreshToken;
    }

    @Data
    public static class LogoutRequest {
        private String userId;
    }
}
