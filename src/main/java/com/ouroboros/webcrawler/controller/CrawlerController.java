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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
            sessions = crawlerManager.getActiveSessions();
        } else {
            sessions = crawlerManager.getAllSessions();
        }

        return ResponseEntity.ok(sessions);
    }

    /**
     * Get a specific crawl session
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<CrawlSession> getSession(@PathVariable("id") String sessionId) {
        log.info("Request to get crawl session: {}", sessionId);
        Optional<CrawlSession> session = crawlerManager.getSession(sessionId);
        return session
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Stop a crawl session
     */
    @PostMapping("/sessions/{id}/stop")
    public ResponseEntity<CrawlSession> stopCrawlSession(@PathVariable("id") String sessionId) {
        log.info("Request to stop crawl session: {}", sessionId);
        CrawlSession stoppedSession = crawlerManager.stopCrawlSession(sessionId);

        if (stoppedSession == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(stoppedSession);
    }

    /**
     * Get stats for a specific crawl session
     */
    @GetMapping("/sessions/{id}/stats")
    public ResponseEntity<Map<String, Object>> getSessionStats(@PathVariable("id") String sessionId) {
        log.info("Request to get stats for crawl session: {}", sessionId);
        Map<String, Long> sessionStats = crawlerManager.getSessionStats(sessionId);

        if (sessionStats.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Get overall frontier stats
        FrontierStats frontierStats = urlFrontier.getStats();

        // Combine stats
        Map<String, Object> combinedStats = new HashMap<>();
        combinedStats.put("session", sessionStats);
        combinedStats.put("frontier", frontierStats);

        return ResponseEntity.ok(combinedStats);
    }

    /**
     * Get overall stats for the crawler
     */
    @GetMapping("/stats")
    public ResponseEntity<FrontierStats> getCrawlerStats() {
        log.info("Request to get overall crawler stats");
        FrontierStats stats = urlFrontier.getStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Quick crawl form handler - processes form submission from the dashboard
     */
    @PostMapping("/crawl/start")
    public String startQuickCrawl(
            @RequestParam("seedUrl") String seedUrl,
            @RequestParam("maxDepth") int maxDepth,
            @RequestParam(value = "respectRobotsTxt", defaultValue = "true") boolean respectRobotsTxt) {

        log.info("Quick crawl request received for URL: {}, maxDepth: {}, respectRobotsTxt: {}",
                seedUrl, maxDepth, respectRobotsTxt);

        CrawlSession session = CrawlSession.builder()
                .name("Quick Crawl: " + seedUrl)
                .seedUrls(Set.of(seedUrl))
                .maxDepth(maxDepth)
                .respectRobotsTxt(respectRobotsTxt)
                .build();

        crawlerManager.startCrawlSession(session);

        // Redirect back to dashboard
        return "redirect:/";
    }
}
