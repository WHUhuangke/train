/*
=====================================================================
火车票务系统初始化脚本 (MySQL)
功能：
1. 重置所有表结构
2. 生成 10 个车次的基础数据
3. 动态生成物理座位 (1-2车厢一等座, 3-5车厢二等座)
4. 严格按照物理座位数初始化全路径 (O-D) 的库存记录
=====================================================================
*/

SET FOREIGN_KEY_CHECKS = 0;

-- 1. 数据库清理与重建
DROP DATABASE IF EXISTS train_platform;
CREATE DATABASE train_platform CHARACTER SET utf8mb4;
USE train_platform;

-- 2. 表结构定义 (确保 ID 自增)
CREATE TABLE `t_train_station` (
                                   `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                                   `train_id` BIGINT NOT NULL,
                                   `station_id` INT NOT NULL,
                                   `station_name` VARCHAR(50),
                                   `sequence` INT NOT NULL COMMENT '站序(0,1,2...)'
) ENGINE=InnoDB;

CREATE TABLE `t_train_seat` (
                                `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                                `train_id` BIGINT NOT NULL,
                                `carriage_num` INT NOT NULL,
                                `row_num` INT NOT NULL,
                                `col_num` VARCHAR(5) NOT NULL,
                                `seat_type` TINYINT COMMENT '1:一等, 2:二等',
                                `seat_bitmap` VARCHAR(64) DEFAULT '000000000' COMMENT '9个区间状态',
                                UNIQUE KEY `uk_seat` (`train_id`, `carriage_num`, `row_num`, `col_num`)
) ENGINE=InnoDB;

CREATE TABLE `t_train_ticket_stock` (
                                        `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                                        `train_id` BIGINT NOT NULL,
                                        `seat_type` TINYINT COMMENT '1:一等, 2:二等',
                                        `from_station_id` INT NOT NULL,
                                        `from_index` INT NOT NULL,
                                        `to_station_id` INT NOT NULL,
                                        `to_index` INT NOT NULL,
                                        `stock` INT DEFAULT 0,
                                        UNIQUE KEY `uk_route_type` (`train_id`, `from_station_id`, `to_station_id`, `seat_type`)
) ENGINE=InnoDB;

CREATE TABLE `t_order` (
                           `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
                           `user_id` BIGINT NOT NULL,
                           `train_id` BIGINT NOT NULL,
                           `seat_id` BIGINT NOT NULL,
                           `from_station_index` INT NOT NULL,
                           `to_station_index` INT NOT NULL,
                           `status` TINYINT DEFAULT 0,
                           `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 消息日志表：用于补偿发送失败的消息，并作为消费端的幂等依据
CREATE TABLE `t_message_log` (
                                 `message_id` varchar(64) NOT NULL,
                                 `content` text NOT NULL COMMENT '消息体JSON',
                                 `status` int DEFAULT '0' COMMENT '0:待处理, 1:已发送到MQ, 2:消费成功, -1:消费失败',
                                 `next_retry` datetime DEFAULT NULL,
                                 `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
                                 PRIMARY KEY (`message_id`)
) ENGINE=InnoDB;

-- 订单表需要确保有 status 字段 (1:待处理, 2:完成, -1:失败)
SET FOREIGN_KEY_CHECKS = 1;

-- 3. 数据初始化存储过程
DELIMITER $$

DROP PROCEDURE IF EXISTS InitTrainSystem$$

CREATE PROCEDURE InitTrainSystem()
BEGIN
    DECLARE i INT DEFAULT 1;         -- 车次
    DECLARE s_from INT DEFAULT 0;    -- 起点站序号
    DECLARE s_to INT DEFAULT 0;      -- 终点站序号
    DECLARE base_id BIGINT;
    DECLARE first_class_count INT;   -- 一等座物理总数
    DECLARE second_class_count INT;  -- 二等座物理总数

    -- 循环生成 10 个车次 (G1001-G1010)
    WHILE i <= 10 DO
        SET base_id = 1000 + i;

        -- A. 生成 10 个站点 (站序 0-9)
        SET s_from = 0;
        WHILE s_from < 10 DO
            INSERT INTO `t_train_station` (train_id, station_id, station_name, sequence)
            VALUES (base_id, s_from + 1, CONCAT('站点_', s_from + 1), s_from);
            SET s_from = s_from + 1;
END WHILE;

        -- B. 生成物理座位 (1-2车厢一等座, 3-5车厢二等座)
        -- 每车厢10行，每行5座 (A,B,C,D,F)
INSERT INTO `t_train_seat` (train_id, carriage_num, row_num, col_num, seat_type, seat_bitmap)
SELECT base_id, c.c_num, r.r_num, col.c_str,
       CASE WHEN c.c_num <= 2 THEN 1 ELSE 2 END,
       '000000000'
FROM
    (SELECT 1 AS c_num UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) c,
    (SELECT 1 AS r_num UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
     UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) r,
    (SELECT 'A' AS c_str UNION SELECT 'B' UNION SELECT 'C' UNION SELECT 'D' UNION SELECT 'F') col;

-- C. 获取该车次真实的座位基数 (物理幂等核心)
SELECT COUNT(*) INTO first_class_count FROM t_train_seat WHERE train_id = base_id AND seat_type = 1;
SELECT COUNT(*) INTO second_class_count FROM t_train_seat WHERE train_id = base_id AND seat_type = 2;

-- D. 生成全区间 (O-D 对) 库存记录
SET s_from = 0;
        WHILE s_from < 9 DO
            SET s_to = s_from + 1;
            WHILE s_to < 10 DO
                -- 插入一等座库存
                INSERT INTO `t_train_ticket_stock` (train_id, seat_type, from_station_id, from_index, to_station_id, to_index, stock)
                VALUES (base_id, 1, s_from + 1, s_from, s_to + 1, s_to, first_class_count);

                -- 插入二等座库存
INSERT INTO `t_train_ticket_stock` (train_id, seat_type, from_station_id, from_index, to_station_id, to_index, stock)
VALUES (base_id, 2, s_from + 1, s_from, s_to + 1, s_to, second_class_count);

SET s_to = s_to + 1;
END WHILE;
            SET s_from = s_from + 1;
END WHILE;

        SET i = i + 1;
END WHILE;
END$$

DELIMITER ;

-- 执行初始化
CALL InitTrainSystem();

-- 4. 验证数据
SELECT '车次总数' as Label, COUNT(DISTINCT train_id) as Value FROM t_train_station
UNION ALL
SELECT '总座位数', COUNT(*) FROM t_train_seat
UNION ALL
SELECT '总库存区间数', COUNT(*) FROM t_train_ticket_stock;