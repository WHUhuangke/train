// RedisInitializer.java - 可选的启动时自动初始化
package com.example.config;

import com.example.service.TrainTicketManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisInitializer implements CommandLineRunner {

    @Autowired
    private TrainTicketManager trainTicketManager;

    @Value("${train.redis.auto-init:false}")
    private boolean autoInit;

    @Value("${train.redis.init-start-id:1001}")
    private Long startTrainId;

    @Value("${train.redis.init-end-id:1010}")
    private Long endTrainId;

    @Override
    public void run(String... args) {
        if (!autoInit) {
            log.info("Redis自动初始化已禁用，请使用API手动初始化");
            return;
        }

        log.info("开始自动初始化Redis缓存，车次范围：{}-{}", startTrainId, endTrainId);
        try {
            trainTicketManager.initializeTrainRange(startTrainId, endTrainId);
            log.info("Redis缓存初始化完成");
        } catch (Exception e) {
            log.error("Redis缓存初始化失败", e);
        }
    }
}
