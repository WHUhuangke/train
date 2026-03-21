package com.example.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 在线连接管理与消息推送服务。
 */
@Slf4j
@Service
public class OrderNotifySseService {

    /** userId -> 多个 SSE 连接（支持同用户多端登录）。 */
    private final Map<Long, List<SseEmitter>> emitterMap = new ConcurrentHashMap<>();

    /**
     * 注册 SSE 连接。
     */
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitterMap.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(ex -> removeEmitter(userId, emitter));
        return emitter;
    }

    /**
     * 向在线用户推送事件。
     *
     * @return true 推送成功；false 用户不在线或全部推送失败。
     */
    public boolean pushToUser(Long userId, String event, Object payload) {
        List<SseEmitter> emitters = emitterMap.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return false;
        }

        boolean hasSuccess = false;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
                hasSuccess = true;
            } catch (IOException e) {
                removeEmitter(userId, emitter);
                log.warn("SSE推送失败，移除连接 userId={}", userId);
            }
        }
        return hasSuccess;
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emitterMap.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emitterMap.remove(userId);
            }
        }
    }
}
