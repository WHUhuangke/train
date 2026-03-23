package com.example.gateway.service;

import com.example.gateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    private final JwtProperties jwtProperties;
    private SecretKey secretKey; // 签名密钥

    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * 初始化方法，在Bean创建后执行
     * 将配置的密钥字符串转换为HMAC-SHA算法所需的SecretKey对象
     */
    @PostConstruct
    public void init() {
        byte[] bytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 为用户颁发Token对（访问令牌 + 刷新令牌）
     * 
     * @param userId 用户ID
     * @param username 用户名
     * @return TokenPair 包含两个Token及相关信息
     */
    public TokenPair issueTokenPair(String userId, String username) {
        Instant now = Instant.now();
        // 生成全局唯一的会话ID，用于SSO会话管理
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        // 1. 生成访问令牌 (Access Token) - 短期有效
        Instant accessExp = now.plus(Duration.ofMinutes(jwtProperties.getExpireMinutes()));
        String accessToken = Jwts.builder()
                .setIssuer(jwtProperties.getIssuer())           // 签发者
                .setSubject(userId)                             // 主题（用户ID）
                .setId(sessionId)                               // 令牌唯一ID（即会话ID）
                .claim("username", username)                     // 自定义声明：用户名
                .claim("type", "access")                        // 自定义声明：令牌类型
                .setIssuedAt(Date.from(now))                    // 签发时间
                .setExpiration(Date.from(accessExp))            // 过期时间
                .signWith(secretKey, SignatureAlgorithm.HS256)  // 签名算法
                .compact();

        // 2. 生成刷新令牌 (Refresh Token) - 长期有效
        Instant refreshExp = now.plus(Duration.ofMinutes(jwtProperties.getRefreshExpireMinutes()));
        String refreshToken = Jwts.builder()
                .setIssuer(jwtProperties.getIssuer())
                .setSubject(userId)
                .setId(sessionId)                               // 与accessToken相同的sessionId
                .claim("username", username)
                .claim("type", "refresh")                       // 类型为refresh
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(refreshExp))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();

        return new TokenPair(accessToken, refreshToken, sessionId, accessExp, refreshExp);
    }

    /**
     * 解析访问令牌
     */
    public TokenClaims parseAccessToken(String token) {
        return parseToken(token, "access");
    }

    /**
     * 解析刷新令牌
     */
    public TokenClaims parseRefreshToken(String token) {
        return parseToken(token, "refresh");
    }

    /**
     * 通用Token解析方法
     * 
     * @param token JWT令牌字符串
     * @param expectedType 期望的令牌类型（"access" 或 "refresh"）
     * @return TokenClaims 令牌声明信息
     * @throws JwtException 当令牌无效、过期、类型不匹配或签发者不匹配时抛出
     */
    private TokenClaims parseToken(String token, String expectedType) {
        if (StringUtils.isBlank(token)) {
            throw new JwtException("token 为空");
        }
        
        // 解析并验证JWT
        Jws<Claims> jws = Jwts.parserBuilder()
                .setSigningKey(secretKey)                     // 设置签名密钥
                .requireIssuer(jwtProperties.getIssuer())     // 要求签发者匹配
                .build()
                .parseClaimsJws(token);

        Claims claims = jws.getBody();
        String type = claims.get("type", String.class);
        
        // 验证令牌类型
        if (!expectedType.equals(type)) {
            throw new JwtException("token 类型不匹配");
        }
        
        // 返回标准化声明对象
        return new TokenClaims(
                claims.getSubject(),                           // userId
                claims.get("username", String.class),         // username
                claims.getId(),                                // sessionId
                claims.getIssuedAt().toInstant(),             // 签发时间
                claims.getExpiration().toInstant()            // 过期时间
        );
    }

    /**
     * Token对包装类
     * 包含访问令牌、刷新令牌及相关元信息
     */
    @Data
    public static class TokenPair {
        private final String accessToken;      // 访问令牌（短期）
        private final String refreshToken;    // 刷新令牌（长期）
        private final String sessionId;       // 会话ID（全局唯一）
        private final Instant accessExpireAt; // 访问令牌过期时间
        private final Instant refreshExpireAt;// 刷新令牌过期时间
    }

    /**
     * 令牌声明信息
     * 包含从JWT中解析出的有效载荷数据
     */
    @Data
    public static class TokenClaims {
        private final String userId;      // 用户ID（JWT的subject）
        private final String username;    // 用户名
        private final String sessionId;    // 会话ID（JWT的jti）
        private final Instant issuedAt;    // 签发时间
        private final Instant expireAt;   // 过期时间
    }
}