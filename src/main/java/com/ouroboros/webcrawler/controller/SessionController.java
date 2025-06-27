package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/sessions")
public class SessionController {

    @Autowired
    private CrawlerManager crawlerManager;

    @GetMapping("")
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

    @GetMapping("/{sessionId}/pages")
    public ResponseEntity<List<CrawledPageEntity>> getSessionPages(@PathVariable String sessionId) {
        List<CrawledPageEntity> pages = crawlerManager.getSessionPages(sessionId);
        return ResponseEntity.ok(pages);
    }
}
