package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/sessions")
@ConditionalOnProperty(name = "webcrawler.enable.session-management", havingValue = "true", matchIfMissing = false)
public class SessionController {

    @Autowired
    private CrawlerManager crawlerManager;

    @GetMapping
    public ResponseEntity<List<CrawlSessionEntity>> getAllSessions() {
        List<CrawlSessionEntity> sessions = crawlerManager.getAllSessions();
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<CrawlSessionEntity> getSession(@PathVariable String sessionId) {
        CrawlSessionEntity session = crawlerManager.getSession(sessionId);
        if (session != null) {
            return ResponseEntity.ok(session);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{sessionId}/pause")
    public ResponseEntity<Void> pauseSession(@PathVariable String sessionId) {
        log.info("Pausing crawl session: {}", sessionId);
        boolean success = crawlerManager.pauseCrawlSession(sessionId);
        if (success) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<Void> resumeSession(@PathVariable String sessionId) {
        log.info("Resuming crawl session: {}", sessionId);
        boolean success = crawlerManager.resumeCrawlSession(sessionId);
        if (success) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<Void> stopSession(@PathVariable String sessionId) {
        log.info("Stopping crawl session: {}", sessionId);
        crawlerManager.stopCrawlSession(sessionId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        log.info("Deleting crawl session: {}", sessionId);
        boolean success = crawlerManager.deleteCrawlSession(sessionId);
        if (success) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{sessionId}/pages")
    public ResponseEntity<List<Object>> getSessionPages(@PathVariable String sessionId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        List<Object> pages = crawlerManager.getSessionPages(sessionId, page, size);
        return ResponseEntity.ok(pages);
    }

    @GetMapping("/{sessionId}/stats")
    public ResponseEntity<Object> getSessionStats(@PathVariable String sessionId) {
        try {
            Object stats = crawlerManager.getSessionQueueStats(sessionId);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting session stats: {}", sessionId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/workers")
    public ResponseEntity<Object> getActiveWorkers() {
        try {
            Object workers = crawlerManager.getActiveWorkers();
            return ResponseEntity.ok(workers);
        } catch (Exception e) {
            log.error("Error getting active workers", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
