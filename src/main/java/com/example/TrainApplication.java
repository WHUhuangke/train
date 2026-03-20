package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import lombok.extern.slf4j.Slf4j;

/**
 * 模拟 12306 抢票平台 - 启动入口
 */
@Slf4j
@SpringBootApplication
public class TrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrainApplication.class, args);
        log.info("====================================================");
        log.info("模拟 12306 抢票平台启动成功！");
        log.info("Redis 端口: 16379");
        log.info("MySQL 端口: 13306");
        log.info("RabbitMQ 管理界面: http://localhost:15672");
        log.info("本地自测地址: http://localhost:8080/api/v1/train/health_check");
        log.info("====================================================");
    }
}