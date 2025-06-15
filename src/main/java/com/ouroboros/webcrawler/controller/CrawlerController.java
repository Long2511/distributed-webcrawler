package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.frontier.FrontierStats;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crawler")
@Slf4j
public class CrawlerController {

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private URLFrontier urlFrontier;

    /**
     * Start a new crawl session
     */
    @PostMapping("/sessions")
    public ResponseEntity<CrawlSession> startCrawlSession(@RequestBody CrawlSession session) {
        log.info("Request to start crawl session with {} seed URLs", session.getSeedUrls().size());
        CrawlSession startedSession = crawlerManager.startCrawlSession(session);
        return ResponseEntity.status(HttpStatus.CREATED).body(startedSession);
    }

    /**
     * Get all crawl sessions
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<CrawlSession>> getAllSessions(
            @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly) {
        log.info("Request to get all crawl sessions, activeOnly={}", activeOnly);
        List<CrawlSession> sessions;

        if (activeOnly) {
            sessions = crawlerManager.getActiveCrawlSessions();
        } else {
            sessions = crawlerManager.getAllCrawlSessions();
        }

        return ResponseEntity.ok(sessions);
    }

    /**
     * Get session by ID
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<CrawlSession> getSession(@PathVariable String sessionId) {
        log.info("Request to get crawl session: {}", sessionId);
        return crawlerManager.getCrawlSession(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Pause a crawl session
     */
    @PostMapping("/sessions/{sessionId}/pause")
    public ResponseEntity<CrawlSession> pauseSession(@PathVariable String sessionId) {
        log.info("Request to pause crawl session: {}", sessionId);
        return crawlerManager.getCrawlSession(sessionId)
                .map(session -> ResponseEntity.ok(crawlerManager.pauseCrawlSession(sessionId)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Resume a paused crawl session
     */
    @PostMapping("/sessions/{sessionId}/resume")
    public ResponseEntity<CrawlSession> resumeSession(@PathVariable String sessionId) {
        log.info("Request to resume crawl session: {}", sessionId);
        return crawlerManager.getCrawlSession(sessionId)
                .map(session -> ResponseEntity.ok(crawlerManager.resumeCrawlSession(sessionId)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Stop a crawl session
     */
    @PostMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<CrawlSession> stopSession(@PathVariable String sessionId) {
        log.info("Request to stop crawl session: {}", sessionId);
        return crawlerManager.getCrawlSession(sessionId)
                .map(session -> ResponseEntity.ok(crawlerManager.stopCrawlSession(sessionId)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get frontier statistics
     */
    @GetMapping("/frontier/stats")
    public ResponseEntity<FrontierStats> getFrontierStats() {
        log.info("Request to get frontier statistics");
        return ResponseEntity.ok(urlFrontier.getStats());
    }
}
