package com.example.controller;

import com.example.dto.StationOptionDTO;
import com.example.dto.TicketAvailabilityDTO;
import com.example.mapper.TrainStationMapper;
import com.example.service.OptimizedRedisTicketService;
import com.example.service.RestTicketService;
import com.example.service.TrainTicketManager;
import com.example.service.OrderNotifySseService;
import com.example.service.BusinessRuleValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 票务 HTTP 控制器。
 *
 * <p>对外暴露查询、抢票、缓存初始化等接口，负责参数校验与返回值封装。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    /** 余票查询服务。 */
    @Autowired
    private RestTicketService ticketService;

    /** 抢票核心服务（支持长区间优先 + 余票缓存预扣减）。 */
    @Autowired
    private OptimizedRedisTicketService t;

    /** 车次初始化管理服务。 */
    @Autowired
    private TrainTicketManager trainTicketManager;

    /** 车站查询 Mapper。 */
    @Autowired
    private TrainStationMapper trainStationMapper;

    /** SSE 通知服务。 */
    @Autowired
    private OrderNotifySseService orderNotifySseService;

    /** 业务规则校验器。 */
    @Autowired
    private BusinessRuleValidator businessRuleValidator;

    /**
     * 查询指定日期与区间的余票。
     */
    @GetMapping("/query")
    public ResponseEntity<List<TicketAvailabilityDTO>> query(
            @RequestParam String departureDate,
            @RequestParam Integer fromStationId,
            @RequestParam Integer toStationId) {

        if (fromStationId == null || toStationId == null || departureDate == null || departureDate.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            LocalDate.parse(departureDate);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().build();
        }

        List<TicketAvailabilityDTO> results = ticketService.queryTicketStock(departureDate, fromStationId, toStationId);

        if (results.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(results);
    }

    /**
     * 获取可选站点列表。
     */
    @GetMapping("/stations")
    public ResponseEntity<List<StationOptionDTO>> stations() {
        List<StationOptionDTO> stations = trainStationMapper.selectStationOptions();
        return ResponseEntity.ok(stations);
    }

    /**
     * 抢票接口：成功返回订单号，失败返回友好文案。
     */
    @PostMapping("/book")
    public ResponseEntity<String> book(
            @RequestParam Long userId,
            @RequestParam Long trainId,
            @RequestParam int fromIndex,
            @RequestParam int toIndex,
            @RequestParam Integer seatType) {
        try {
            businessRuleValidator.validateBookRequest(userId, trainId, fromIndex, toIndex, seatType);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body("请求参数非法：" + ex.getMessage());
        }

        String success = t.bookTicket(userId, trainId, fromIndex, toIndex, seatType);

        if (success != null) {
            log.info("抢票成功, orderId={}", success);
            return ResponseEntity.ok("抢票成功，订单已生成，订单号:" + success);
        } else {
            return ResponseEntity.ok().body("抢票失败，无可用座位");
        }
    }

    /**
     * 初始化指定车次缓存（支持指定座位类型）。
     */
    @PostMapping("/initialize")
    public ResponseEntity<String> initialize(
            @RequestParam Long trainId,
            @RequestParam(required = false) Integer seatType) {

        try {
            if (seatType != null) {
                trainTicketManager.initializeTrain(trainId, seatType);
                log.info("车次{}座位类型{}初始化成功", trainId, seatType);
            } else {
                trainTicketManager.initializeTrainAllTypes(trainId);
                log.info("车次{}所有座位类型初始化成功", trainId);
            }
            return ResponseEntity.ok("初始化成功");
        } catch (Exception e) {
            log.error("初始化失败", e);
            return ResponseEntity.internalServerError().body("初始化失败：" + e.getMessage());
        }
    }

    /**
     * 批量初始化车次缓存。
     */
    @PostMapping("/initialize/batch")
    public ResponseEntity<String> initializeBatch(
            @RequestParam Long startTrainId,
            @RequestParam Long endTrainId) {

        try {
            trainTicketManager.initializeTrainRange(startTrainId, endTrainId);
            log.info("批量初始化车次{}-{}成功", startTrainId, endTrainId);
            return ResponseEntity.ok("批量初始化成功");
        } catch (Exception e) {
            log.error("批量初始化失败", e);
            return ResponseEntity.internalServerError().body("批量初始化失败：" + e.getMessage());
        }
    }


    /**
     * SSE 订阅接口：前端通过 EventSource 建立长连接接收订单完成通知。
     */
    @GetMapping("/subscribe")
    public SseEmitter subscribe(@RequestParam Long userId) {
        return orderNotifySseService.subscribe(userId);
    }

    /**
     * 查询剩余配额。
     */
    @GetMapping("/quota")
    public ResponseEntity<Long> getQuota(@RequestParam Long trainId) {
        long remaining = trainTicketManager.getRemainingQuota(trainId, 2000);
        return ResponseEntity.ok(remaining);
    }
}
