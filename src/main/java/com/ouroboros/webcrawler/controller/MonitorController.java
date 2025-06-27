package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.metrics.CrawlerMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/monitor")
public class MonitorController {

    @Autowired
    private CrawlerMetrics crawlerMetrics;

    @GetMapping("/metrics")
    public ResponseEntity<CrawlerMetrics.SystemMetrics> getSystemMetrics() {
        CrawlerMetrics.SystemMetrics metrics = crawlerMetrics.getSystemMetrics();
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/metrics/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedMetrics() {
        Map<String, Object> metrics = crawlerMetrics.getDetailedMetrics();
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> getHealth() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }
}
