package com.example.gateway.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class SsoSessionService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public SsoSessionService(ReactiveStringRedisTemplate redisTemplate,
                             @Value("${gateway.sso.session-prefix:gateway:sso:user:}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    public Mono<Boolean> bindSession(String userId, String sessionId, Duration ttl) {
        return redisTemplate.opsForValue().set(buildKey(userId), sessionId, ttl);
    }

    public Mono<Boolean> isCurrentSession(String userId, String sessionId) {
        return redisTemplate.opsForValue().get(buildKey(userId))
                .map(stored -> stored.equals(sessionId))
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> clearSession(String userId) {
        return redisTemplate.delete(buildKey(userId)).map(deleted -> deleted > 0);
    }

    private String buildKey(String userId) {
        return keyPrefix + userId;
    }
}
