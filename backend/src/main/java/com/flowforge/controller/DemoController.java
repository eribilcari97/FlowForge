package com.flowforge.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("dev")
@RestController
@RequestMapping("/demo")
public class DemoController {

    private static final int MAX_SLOW_SECONDS = 60;

    private final AtomicInteger flakyCalls = new AtomicInteger();

    @RequestMapping("/orders")
    Map<String, Object> orders() {
        List<Map<String, Object>> orders = List.of(
                Map.of("id", "o-1001", "customer", "jane@example.com", "total", 25.50, "status", "PAID"),
                Map.of("id", "o-1002", "customer", "ali@example.com", "total", 99.00, "status", "PAID"),
                Map.of("id", "o-1003", "customer", "jane@example.com", "total", 12.25, "status", "REFUNDED"));
        return Map.of("orders", orders, "count", orders.size());
    }

    @RequestMapping("/flaky")
    ResponseEntity<Map<String, Object>> flaky() {
        int call = flakyCalls.incrementAndGet();
        if (call % 3 != 0) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Temporarily unavailable", "call", call));
        }
        return ResponseEntity.ok(Map.of("ok", true, "call", call));
    }

    @RequestMapping("/slow")
    Map<String, Object> slow(@RequestParam(defaultValue = "5") int seconds) throws InterruptedException {
        int sleep = Math.max(0, Math.min(seconds, MAX_SLOW_SECONDS));
        Thread.sleep(sleep * 1000L);
        return Map.of("sleptSeconds", sleep);
    }
}
