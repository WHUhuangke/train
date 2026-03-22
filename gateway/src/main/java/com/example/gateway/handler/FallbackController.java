package com.example.gateway.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping(value = "/fallback/ticket", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ticketFallback() {
        return buildFallback("ticket-service-busy", "票务查询服务繁忙，请稍后重试");
    }

    @GetMapping(value = "/fallback/common", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> commonFallback() {
        return buildFallback("gateway-fallback", "网关触发限流/熔断，请稍后重试");
    }

    private ResponseEntity<Map<String, Object>> buildFallback(String code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", msg);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }
}
