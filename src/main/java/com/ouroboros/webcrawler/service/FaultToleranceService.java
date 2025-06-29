package com.ouroboros.webcrawler.service;

import com.ouroboros.webcrawler.entity.CrawlUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class FaultToleranceService {

    @Value("${webcrawler.fault-tolerance.max-retries:3}")
    private int maxRetries;

    @Value("${webcrawler.fault-tolerance.retry-delay-ms:5000}")
    private long retryDelayMs;

    @Value("${webcrawler.fault-tolerance.circuit-breaker.threshold:5}")
    private int circuitBreakerThreshold;

    @Value("${webcrawler.fault-tolerance.circuit-breaker.timeout-ms:30000}")
    private long circuitBreakerTimeoutMs;

    // Circuit breaker state for each domain
    private final Map<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    // Retry tracking for URLs
    private final Map<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    public boolean shouldRetry(CrawlUrl crawlUrl, String errorMessage) {
        String url = crawlUrl.getUrl();
        String domain = extractDomain(url);
        
        // Check circuit breaker
        if (isCircuitBreakerOpen(domain)) {
            log.warn("Circuit breaker is OPEN for domain: {}, skipping retry for URL: {}", domain, url);
            return false;
        }

        // Check retry count
        AtomicInteger retryCount = retryCounters.computeIfAbsent(url, k -> new AtomicInteger(0));
        int currentRetries = retryCount.get();
        
        if (currentRetries >= maxRetries) {
            log.warn("Max retries ({}) exceeded for URL: {}, last error: {}", maxRetries, url, errorMessage);
            return false;
        }

        // Increment retry count
        retryCount.incrementAndGet();
        log.info("Scheduling retry {} for URL: {} (domain: {})", currentRetries + 1, url, domain);
        
        return true;
    }

    public void recordFailure(String url, String errorMessage) {
        String domain = extractDomain(url);
        CircuitBreakerState state = circuitBreakers.computeIfAbsent(domain, k -> new CircuitBreakerState());
        
        state.recordFailure();
        
        if (state.getFailureCount() >= circuitBreakerThreshold && state.getState() == CircuitBreakerState.State.CLOSED) {
            state.setState(CircuitBreakerState.State.OPEN);
            state.setLastFailureTime(LocalDateTime.now());
            log.warn("Circuit breaker OPENED for domain: {} after {} failures", domain, state.getFailureCount());
        }
    }

    public void recordSuccess(String url) {
        String domain = extractDomain(url);
        CircuitBreakerState state = circuitBreakers.get(domain);
        
        if (state != null) {
            state.recordSuccess();
            
            if (state.getState() == CircuitBreakerState.State.OPEN && state.getConsecutiveSuccesses() >= 2) {
                state.setState(CircuitBreakerState.State.HALF_OPEN);
                log.info("Circuit breaker HALF-OPEN for domain: {}", domain);
            } else if (state.getState() == CircuitBreakerState.State.HALF_OPEN && state.getConsecutiveSuccesses() >= 3) {
                state.setState(CircuitBreakerState.State.CLOSED);
                state.reset();
                log.info("Circuit breaker CLOSED for domain: {}", domain);
            }
        }
    }

    public boolean isCircuitBreakerOpen(String domain) {
        CircuitBreakerState state = circuitBreakers.get(domain);
        if (state == null) {
            return false;
        }

        if (state.getState() == CircuitBreakerState.State.OPEN) {
            // Check if timeout has passed
            if (LocalDateTime.now().isAfter(state.getLastFailureTime().plusNanos(circuitBreakerTimeoutMs * 1_000_000))) {
                state.setState(CircuitBreakerState.State.HALF_OPEN);
                log.info("Circuit breaker timeout expired for domain: {}, transitioning to HALF-OPEN", domain);
                return false;
            }
            return true;
        }

        return false;
    }

    public long getRetryDelay(String url) {
        String domain = extractDomain(url);
        AtomicInteger retryCount = retryCounters.get(url);
        int currentRetries = retryCount != null ? retryCount.get() : 0;
        
        // Exponential backoff: delay * 2^(retry_count)
        return retryDelayMs * (long) Math.pow(2, currentRetries);
    }

    public void cleanupRetryCounters() {
        // Clean up old retry counters periodically
        retryCounters.entrySet().removeIf(entry -> {
            // Remove entries older than 1 hour (simplified cleanup)
            return true; // For now, clean all entries
        });
    }

    private String extractDomain(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            log.warn("Could not extract domain from URL: {}", url);
            return "unknown";
        }
    }

    public Map<String, Object> getFaultToleranceStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("circuitBreakers", circuitBreakers.size());
        stats.put("retryCounters", retryCounters.size());
        
        Map<String, Object> circuitBreakerStats = new java.util.HashMap<>();
        circuitBreakers.forEach((domain, state) -> {
            Map<String, Object> domainStats = new java.util.HashMap<>();
            domainStats.put("state", state.getState().name());
            domainStats.put("failureCount", state.getFailureCount());
            domainStats.put("consecutiveSuccesses", state.getConsecutiveSuccesses());
            domainStats.put("lastFailureTime", state.getLastFailureTime());
            circuitBreakerStats.put(domain, domainStats);
        });
        stats.put("circuitBreakerDetails", circuitBreakerStats);
        
        return stats;
    }

    private static class CircuitBreakerState {
        private State state = State.CLOSED;
        private int failureCount = 0;
        private int consecutiveSuccesses = 0;
        private LocalDateTime lastFailureTime;

        public enum State {
            CLOSED, OPEN, HALF_OPEN
        }

        public void recordFailure() {
            failureCount++;
            consecutiveSuccesses = 0;
            lastFailureTime = LocalDateTime.now();
        }

        public void recordSuccess() {
            consecutiveSuccesses++;
            if (state == State.CLOSED) {
                failureCount = Math.max(0, failureCount - 1);
            }
        }

        public void reset() {
            failureCount = 0;
            consecutiveSuccesses = 0;
        }

        // Getters and setters
        public State getState() { return state; }
        public void setState(State state) { this.state = state; }
        public int getFailureCount() { return failureCount; }
        public int getConsecutiveSuccesses() { return consecutiveSuccesses; }
        public LocalDateTime getLastFailureTime() { return lastFailureTime; }
        public void setLastFailureTime(LocalDateTime lastFailureTime) { this.lastFailureTime = lastFailureTime; }
    }
} 