package com.ouroboros.webcrawler.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouroboros.webcrawler.metrics.CrawlerMetrics;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/monitor")
public class MonitorController {

    @Autowired
    private CrawlerMetrics crawlerMetrics;

    @Autowired
    private CrawledPageRepository pageRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final int DEFAULT_RECENT_URL_LIMIT = 25;
    private static final String WORKER_REGISTRY_KEY = "workers:active";
    private static final String HEARTBEAT_KEY_PREFIX = "heartbeat:";

    /**
     * Return the most recently crawled URLs (limited list) for real-time UI display
     */
    @GetMapping("/recent-urls")
    public ResponseEntity<List<RecentUrl>> getRecentUrls(@RequestParam(name = "limit", required = false) Integer limit) {
        int fetchSize = limit != null && limit > 0 ? limit : DEFAULT_RECENT_URL_LIMIT;
        PageRequest pageRequest = PageRequest.of(0, fetchSize, Sort.by(Sort.Direction.DESC, "crawlTime"));
        List<RecentUrl> recent = pageRepository.findAll(pageRequest).getContent()
                .stream()
                .map(p -> new RecentUrl(p.getUrl(), mapStatus(p.getStatusCode()), p.getCrawlTime() != null ? p.getCrawlTime().toInstant(ZoneOffset.UTC).toEpochMilli() : 0L))
                .collect(Collectors.toList());
        return ResponseEntity.ok(recent);
    }

    /**
     * Return currently registered crawler instances and their heartbeat status
     */
    @GetMapping("/instances")
    public ResponseEntity<List<InstanceStatus>> getInstanceStatus() {
        Set<Object> raw = redisTemplate.opsForSet().members(WORKER_REGISTRY_KEY);
        if (raw == null) raw = Collections.emptySet();
        List<InstanceStatus> list = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (Object obj : raw) {
            try {
                JsonNode node = mapper.readTree(String.valueOf(obj));
                String id = node.path("id").asText();
                String host = node.path("host").asText();
                String heartbeatKey = HEARTBEAT_KEY_PREFIX + id;
                Object hb = redisTemplate.opsForValue().get(heartbeatKey);
                long lastHeartbeatTs = 0L;
                if (hb != null) {
                    try {
                        lastHeartbeatTs = LocalDateTime.parse(String.valueOf(hb)).toInstant(ZoneOffset.UTC).toEpochMilli();
                    } catch (Exception ignored) {}
                }
                String status = hb != null ? "ONLINE" : "OFFLINE";
                list.add(new InstanceStatus(id, host, status, lastHeartbeatTs, 0, 0d, 0d));
            } catch (Exception e) {
                log.warn("Unable to parse worker registry entry: {}", obj, e);
            }
        }
        return ResponseEntity.ok(list);
    }

    // Helper methods / DTOs
    private String mapStatus(int code) {
        if (code >= 200 && code < 300) return "COMPLETED";
        if (code >= 400) return "FAILED";
        return "OTHER";
    }

    private static class RecentUrl {
        private final String url;
        private final String status;
        private final long timestamp;

        public RecentUrl(String url, String status, long timestamp) {
            this.url = url;
            this.status = status;
            this.timestamp = timestamp;
        }

        public String getUrl() { return url; }
        public String getStatus() { return status; }
        public long getTimestamp() { return timestamp; }
    }

    private static class InstanceStatus {
        private final String instanceId;
        private final String host;
        private final String status;
        private final long lastHeartbeat;
        private final int activeTasks;
        private final double cpuUsage;
        private final double memoryUsage;

        public InstanceStatus(String instanceId, String host, String status, long lastHeartbeat, int activeTasks, double cpuUsage, double memoryUsage) {
            this.instanceId = instanceId;
            this.host = host;
            this.status = status;
            this.lastHeartbeat = lastHeartbeat;
            this.activeTasks = activeTasks;
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
        }

        public String getInstanceId() { return instanceId; }
        public String getHost() { return host; }
        public String getStatus() { return status; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public int getActiveTasks() { return activeTasks; }
        public double getCpuUsage() { return cpuUsage; }
        public double getMemoryUsage() { return memoryUsage; }
    }

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
