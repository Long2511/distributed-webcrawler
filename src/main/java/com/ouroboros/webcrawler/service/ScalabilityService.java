package com.ouroboros.webcrawler.service;

import com.ouroboros.webcrawler.config.DistributedInstanceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ScalabilityService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DistributedInstanceConfig instanceConfig;

    @Value("${webcrawler.scalability.worker-timeout-seconds:120}")
    private int workerTimeoutSeconds;

    @Value("${webcrawler.scalability.max-workers-per-session:10}")
    private int maxWorkersPerSession;

    @Value("${webcrawler.scalability.load-balancing.enabled:true}")
    private boolean loadBalancingEnabled;

    // Active workers tracking
    private final Map<String, WorkerInfo> activeWorkers = new ConcurrentHashMap<>();
    
    // Session workload distribution
    private final Map<String, SessionWorkload> sessionWorkloads = new ConcurrentHashMap<>();

    private static final String WORKER_REGISTRY_KEY = "workers:active";
    private static final String WORKER_HEARTBEAT_PREFIX = "heartbeat:";

    public void registerWorker(String workerId, String host, int port) {
        WorkerInfo worker = new WorkerInfo(workerId, host, port);
        activeWorkers.put(workerId, worker);
        
        // Register in Redis for distributed coordination
        String workerInfo = String.format("{\"id\":\"%s\",\"host\":\"%s\",\"port\":%d,\"registeredAt\":\"%s\"}", 
            workerId, host, port, LocalDateTime.now());
        redisTemplate.opsForSet().add(WORKER_REGISTRY_KEY, workerInfo);
        
        log.info("Registered worker: {} at {}:{}", workerId, host, port);
    }

    public void unregisterWorker(String workerId) {
        activeWorkers.remove(workerId);
        
        // Remove from Redis
        Set<Object> workers = redisTemplate.opsForSet().members(WORKER_REGISTRY_KEY);
        if (workers != null) {
            workers.forEach(worker -> {
                if (worker.toString().contains(workerId)) {
                    redisTemplate.opsForSet().remove(WORKER_REGISTRY_KEY, worker);
                }
            });
        }
        
        log.info("Unregistered worker: {}", workerId);
    }

    public void updateWorkerHeartbeat(String workerId) {
        WorkerInfo worker = activeWorkers.get(workerId);
        if (worker != null) {
            worker.setLastHeartbeat(LocalDateTime.now());
            worker.setStatus(WorkerStatus.ACTIVE);
        }
    }

    public List<String> getAvailableWorkers() {
        List<String> availableWorkers = new ArrayList<>();
        
        for (Map.Entry<String, WorkerInfo> entry : activeWorkers.entrySet()) {
            WorkerInfo worker = entry.getValue();
            if (worker.getStatus() == WorkerStatus.ACTIVE && 
                worker.getLastHeartbeat().isAfter(LocalDateTime.now().minusSeconds(workerTimeoutSeconds))) {
                availableWorkers.add(entry.getKey());
            }
        }
        
        return availableWorkers;
    }

    public String selectOptimalWorker(String sessionId) {
        if (!loadBalancingEnabled) {
            // Simple round-robin
            List<String> availableWorkers = getAvailableWorkers();
            if (availableWorkers.isEmpty()) {
                return null;
            }
            return availableWorkers.get(new Random().nextInt(availableWorkers.size()));
        }

        // Load-based selection
        return selectWorkerByLoad(sessionId);
    }

    private String selectWorkerByLoad(String sessionId) {
        List<String> availableWorkers = getAvailableWorkers();
        if (availableWorkers.isEmpty()) {
            return null;
        }

        // Get current workload for each worker
        Map<String, Integer> workerLoads = new HashMap<>();
        for (String workerId : availableWorkers) {
            WorkerInfo worker = activeWorkers.get(workerId);
            if (worker != null) {
                workerLoads.put(workerId, worker.getCurrentLoad());
            }
        }

        // Select worker with lowest load
        return workerLoads.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    public void updateWorkerLoad(String workerId, int load) {
        WorkerInfo worker = activeWorkers.get(workerId);
        if (worker != null) {
            worker.setCurrentLoad(load);
        }
    }

    public void recordSessionWorkload(String sessionId, int urlsDiscovered, int urlsCrawled) {
        SessionWorkload workload = sessionWorkloads.computeIfAbsent(sessionId, k -> new SessionWorkload());
        workload.setUrlsDiscovered(urlsDiscovered);
        workload.setUrlsCrawled(urlsCrawled);
        workload.setLastUpdated(LocalDateTime.now());
    }

    public boolean shouldScaleUp(String sessionId) {
        SessionWorkload workload = sessionWorkloads.get(sessionId);
        if (workload == null) {
            return false;
        }

        List<String> availableWorkers = getAvailableWorkers();
        int currentWorkers = availableWorkers.size();
        
        // Scale up if we have high workload and available capacity
        return workload.getUrlsDiscovered() > workload.getUrlsCrawled() * 2 && 
               currentWorkers < maxWorkersPerSession;
    }

    public boolean shouldScaleDown(String sessionId) {
        SessionWorkload workload = sessionWorkloads.get(sessionId);
        if (workload == null) {
            return false;
        }

        List<String> availableWorkers = getAvailableWorkers();
        int currentWorkers = availableWorkers.size();
        
        // Scale down if workload is low
        return workload.getUrlsDiscovered() <= workload.getUrlsCrawled() && 
               currentWorkers > 1;
    }

    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void cleanupInactiveWorkers() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(workerTimeoutSeconds);
        
        List<String> inactiveWorkers = new ArrayList<>();
        for (Map.Entry<String, WorkerInfo> entry : activeWorkers.entrySet()) {
            WorkerInfo worker = entry.getValue();
            if (worker.getLastHeartbeat().isBefore(cutoff)) {
                worker.setStatus(WorkerStatus.INACTIVE);
                inactiveWorkers.add(entry.getKey());
                log.warn("Worker {} marked as inactive due to missing heartbeat", entry.getKey());
            }
        }
        
        // Remove inactive workers after a grace period
        inactiveWorkers.forEach(workerId -> {
            WorkerInfo worker = activeWorkers.get(workerId);
            if (worker != null && worker.getLastHeartbeat().isBefore(cutoff.minusSeconds(60))) {
                unregisterWorker(workerId);
                log.info("Removed inactive worker: {}", workerId);
            }
        });
    }

    @Scheduled(fixedRate = 60000) // Every minute
    public void rebalanceWorkload() {
        if (!loadBalancingEnabled) {
            return;
        }

        // Simple workload rebalancing logic
        for (String sessionId : sessionWorkloads.keySet()) {
            if (shouldScaleUp(sessionId)) {
                log.info("Session {} needs scale up - high workload detected", sessionId);
            } else if (shouldScaleDown(sessionId)) {
                log.info("Session {} can scale down - low workload detected", sessionId);
            }
        }
    }

    public Map<String, Object> getScalabilityStats() {
        Map<String, Object> stats = new HashMap<>();
        
        List<String> availableWorkers = getAvailableWorkers();
        stats.put("totalWorkers", activeWorkers.size());
        stats.put("activeWorkers", availableWorkers.size());
        stats.put("inactiveWorkers", activeWorkers.size() - availableWorkers.size());
        stats.put("sessionWorkloads", sessionWorkloads.size());
        
        // Worker details
        Map<String, Object> workerDetails = new HashMap<>();
        activeWorkers.forEach((workerId, worker) -> {
            Map<String, Object> workerStats = new HashMap<>();
            workerStats.put("host", worker.getHost());
            workerStats.put("port", worker.getPort());
            workerStats.put("status", worker.getStatus().name());
            workerStats.put("currentLoad", worker.getCurrentLoad());
            workerStats.put("lastHeartbeat", worker.getLastHeartbeat());
            workerDetails.put(workerId, workerStats);
        });
        stats.put("workerDetails", workerDetails);
        
        return stats;
    }

    private static class WorkerInfo {
        private String id;
        private String host;
        private int port;
        private WorkerStatus status = WorkerStatus.ACTIVE;
        private LocalDateTime lastHeartbeat = LocalDateTime.now();
        private int currentLoad = 0;

        public WorkerInfo(String id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
        }

        // Getters and setters
        public String getId() { return id; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public WorkerStatus getStatus() { return status; }
        public void setStatus(WorkerStatus status) { this.status = status; }
        public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
        public int getCurrentLoad() { return currentLoad; }
        public void setCurrentLoad(int currentLoad) { this.currentLoad = currentLoad; }
    }

    private static class SessionWorkload {
        private int urlsDiscovered = 0;
        private int urlsCrawled = 0;
        private LocalDateTime lastUpdated = LocalDateTime.now();

        // Getters and setters
        public int getUrlsDiscovered() { return urlsDiscovered; }
        public void setUrlsDiscovered(int urlsDiscovered) { this.urlsDiscovered = urlsDiscovered; }
        public int getUrlsCrawled() { return urlsCrawled; }
        public void setUrlsCrawled(int urlsCrawled) { this.urlsCrawled = urlsCrawled; }
        public LocalDateTime getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
    }

    public enum WorkerStatus {
        ACTIVE, INACTIVE, FAILED
    }
} 