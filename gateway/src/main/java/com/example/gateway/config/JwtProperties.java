package com.example.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "gateway.jwt")
public class JwtProperties {

    /**
     * 建议使用至少 32 位随机字符串，并通过环境变量覆盖。
     */
    private String secret = "change-this-to-a-very-long-random-secret-key";

    /**
     * access token 有效期（分钟）。
     */
    private long expireMinutes = 30;

    /**
     * 刷新 token 有效期（分钟）。
     */
    private long refreshExpireMinutes = 1440;

    /**
     * 发行方标识。
     */
    private String issuer = "train-ticket-gateway";
}
