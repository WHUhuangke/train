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
    private SecretKey secretKey;

    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    public void init() {
        byte[] bytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(bytes);
    }

    public TokenPair issueTokenPair(String userId, String username) {
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        Instant accessExp = now.plus(Duration.ofMinutes(jwtProperties.getExpireMinutes()));
        String accessToken = Jwts.builder()
                .setIssuer(jwtProperties.getIssuer())
                .setSubject(userId)
                .setId(sessionId)
                .claim("username", username)
                .claim("type", "access")
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(accessExp))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();

        Instant refreshExp = now.plus(Duration.ofMinutes(jwtProperties.getRefreshExpireMinutes()));
        String refreshToken = Jwts.builder()
                .setIssuer(jwtProperties.getIssuer())
                .setSubject(userId)
                .setId(sessionId)
                .claim("username", username)
                .claim("type", "refresh")
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(refreshExp))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();

        return new TokenPair(accessToken, refreshToken, sessionId, accessExp, refreshExp);
    }

    public TokenClaims parseAccessToken(String token) {
        return parseToken(token, "access");
    }

    public TokenClaims parseRefreshToken(String token) {
        return parseToken(token, "refresh");
    }

    private TokenClaims parseToken(String token, String expectedType) {
        if (StringUtils.isBlank(token)) {
            throw new JwtException("token 为空");
        }
        Jws<Claims> jws = Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseClaimsJws(token);

        Claims claims = jws.getBody();
        String type = claims.get("type", String.class);
        if (!expectedType.equals(type)) {
            throw new JwtException("token 类型不匹配");
        }
        return new TokenClaims(
                claims.getSubject(),
                claims.get("username", String.class),
                claims.getId(),
                claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant()
        );
    }

    @Data
    public static class TokenPair {
        private final String accessToken;
        private final String refreshToken;
        private final String sessionId;
        private final Instant accessExpireAt;
        private final Instant refreshExpireAt;
    }

    @Data
    public static class TokenClaims {
        private final String userId;
        private final String username;
        private final String sessionId;
        private final Instant issuedAt;
        private final Instant expireAt;
    }
}
