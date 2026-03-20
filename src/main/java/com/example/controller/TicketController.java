package com.example.controller;

import com.example.dto.StationOptionDTO;
import com.example.dto.TicketAvailabilityDTO;
import com.example.mapper.TrainStationMapper;
import com.example.service.OptimizedRedisTicketService;
import com.example.service.RestTicketService;
import com.example.service.TrainTicketManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    @Autowired
    private RestTicketService ticketService;

    @Autowired
    private OptimizedRedisTicketService t;

    @Autowired
    private TrainTicketManager trainTicketManager;

    @Autowired
    private TrainStationMapper trainStationMapper;

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

    @GetMapping("/stations")
    public ResponseEntity<List<StationOptionDTO>> stations() {
        List<StationOptionDTO> stations = trainStationMapper.selectStationOptions();
        return ResponseEntity.ok(stations);
    }

    @PostMapping("/book")
    public ResponseEntity<String> book(
            @RequestParam Long userId,
            @RequestParam Long trainId,
            @RequestParam int fromIndex,
            @RequestParam int toIndex,
            @RequestParam Integer seatType) {

        String success = t.bookTicket(userId, trainId, fromIndex, toIndex, seatType);

        if (success != null) {
            log.info("success");
            return ResponseEntity.ok("抢票成功，订单已生成，订单号:" + success);
        } else {
            return ResponseEntity.ok().body("抢票失败，无可用座位");
        }
    }

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

    @GetMapping("/quota")
    public ResponseEntity<Long> getQuota(@RequestParam Long trainId) {
        long remaining = trainTicketManager.getRemainingQuota(trainId, 2000);
        return ResponseEntity.ok(remaining);
    }
}
