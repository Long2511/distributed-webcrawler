package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API controller for sessions page
 */
@RestController
@RequestMapping("/api/sessions")
@Slf4j
public class SessionsApiController {

    @Autowired
    private CrawlerManager crawlerManager;

    /**
     * Get all sessions with their progress information
     */
    @GetMapping("")
    public ResponseEntity<List<Map<String, Object>>> getAllSessions(
            @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly) {

        log.debug("API - Fetching all sessions, activeOnly={}", activeOnly);
        List<CrawlSession> sessions;

        if (activeOnly) {
            sessions = crawlerManager.getActiveSessions();
        } else {
            sessions = crawlerManager.getAllSessions();
        }

        // Transform sessions to include progress information
        List<Map<String, Object>> sessionData = sessions.stream().map(session -> {
            Map<String, Object> data = new HashMap<>();
            data.put("id", session.getId());
            data.put("name", session.getName());
            data.put("status", session.getStatus());
            data.put("seedUrls", session.getSeedUrls());
            data.put("createdAt", session.getCreatedAt());
            data.put("description", session.getDescription());

            // Calculate progress from session stats
            Map<String, Long> stats = crawlerManager.getSessionStats(session.getId());
            long total = stats.getOrDefault("totalUrls", 0L);
            long completed = stats.getOrDefault("completedUrls", 0L);

            int progress = 0;
            if (total > 0) {
                progress = (int) ((completed * 100) / total);
            }
            data.put("progress", progress);

            return data;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(sessionData);
    }

    /**
     * Get session statistics summary
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSessionStats() {
        log.debug("API - Fetching session statistics summary");
        Map<String, Object> stats = new HashMap<>();

        List<CrawlSession> allSessions = crawlerManager.getAllSessions();
        List<CrawlSession> activeSessions = crawlerManager.getActiveSessions();

        stats.put("totalSessions", allSessions.size());
        stats.put("activeSessions", activeSessions.size());

        long completedSessions = allSessions.stream()
                .filter(s -> s.getStatus().equals("COMPLETED"))
                .count();
        stats.put("completedSessions", completedSessions);

        // Calculate average session duration for completed sessions
        double avgDuration = allSessions.stream()
                .filter(s -> s.getStatus().equals("COMPLETED") && s.getCompletedAt() != null && s.getCreatedAt() != null)
                .mapToDouble(s -> {
                    long durationMillis = java.time.Duration.between(s.getCreatedAt(), s.getCompletedAt()).toMillis();
                    return durationMillis / 60000.0; // convert to minutes
                })
                .average()
                .orElse(0.0);

        stats.put("avgSessionDuration", Math.round(avgDuration * 10) / 10.0);  // Round to 1 decimal place

        return ResponseEntity.ok(stats);
    }

    /**
     * Pause a session
     */
    @PostMapping("/{sessionId}/pause")
    public ResponseEntity<?> pauseSession(@PathVariable String sessionId) {
        log.info("API - Pausing session with ID: {}", sessionId);

        try {
            crawlerManager.pauseSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to pause session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to pause session: " + e.getMessage()));
        }
    }

    /**
     * Resume a session
     */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<?> resumeSession(@PathVariable String sessionId) {
        log.info("API - Resuming session with ID: {}", sessionId);

        try {
            crawlerManager.resumeSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to resume session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to resume session: " + e.getMessage()));
        }
    }

    /**
     * Delete a session
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<?> deleteSession(@PathVariable String sessionId) {
        log.info("API - Deleting session with ID: {}", sessionId);

        try {
            crawlerManager.deleteSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to delete session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to delete session: " + e.getMessage()));
        }
    }
}
