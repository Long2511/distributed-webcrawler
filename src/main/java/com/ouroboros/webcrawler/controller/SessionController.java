package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.entity.CrawlSessionEntity;
import com.ouroboros.webcrawler.entity.CrawledPageEntity;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.repository.CrawlSessionRepository;
import com.ouroboros.webcrawler.repository.CrawledPageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/sessions")
public class SessionController {

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private CrawlSessionRepository sessionRepository;

    @Autowired
    private CrawledPageRepository pageRepository;

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
    public ResponseEntity<List<CrawledPageEntity>> getSessionPages(@PathVariable String sessionId,
                                                                  @RequestParam(name = "status", required = false) String status) {
        List<CrawledPageEntity> pages = crawlerManager.getSessionPages(sessionId);

        if (status != null && !status.equalsIgnoreCase("all")) {
            pages = pages.stream().filter(p -> {
                int code = p.getStatusCode();
                switch (status.toLowerCase()) {
                    case "completed":
                        return code >= 200 && code < 300;
                    case "failed":
                        return code >= 400;
                    case "pending":
                        return code == 0;
                    default:
                        return true;
                }
            }).collect(Collectors.toList());
        }

        // Sort latest first
        pages.sort((a, b) -> {
            if (a.getCrawlTime() == null) return 1;
            if (b.getCrawlTime() == null) return -1;
            return b.getCrawlTime().compareTo(a.getCrawlTime());
        });

        return ResponseEntity.ok(pages);
    }

    // Pause session
    @PostMapping("/{sessionId}/pause")
    public ResponseEntity<Void> pauseSession(@PathVariable String sessionId) {
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !"RUNNING".equals(session.getStatus())) {
            return ResponseEntity.badRequest().build();
        }
        session.setStatus("PAUSED");
        sessionRepository.save(session);
        return ResponseEntity.ok().build();
    }

    // Resume session
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<Void> resumeSession(@PathVariable String sessionId) {
        CrawlSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !"PAUSED".equals(session.getStatus())) {
            return ResponseEntity.badRequest().build();
        }
        session.setStatus("RUNNING");
        sessionRepository.save(session);
        return ResponseEntity.ok().build();
    }

    // Stop session
    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<Void> stopSession(@PathVariable String sessionId) {
        crawlerManager.stopCrawlSession(sessionId);
        return ResponseEntity.ok().build();
    }

    // Delete session and associated pages
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionRepository.deleteById(sessionId);
        // Remove crawled pages
        pageRepository.findBySessionId(sessionId).forEach(pageRepository::delete);
        return ResponseEntity.ok().build();
    }

    // Export session pages as CSV
    @GetMapping("/{sessionId}/export")
    public ResponseEntity<Resource> exportSession(@PathVariable String sessionId) {
        List<CrawledPageEntity> pages = pageRepository.findBySessionId(sessionId);

        StringBuilder sb = new StringBuilder();
        sb.append("url,statusCode,contentType\n");
        pages.forEach(p -> sb.append(p.getUrl()).append(',')
                .append(p.getStatusCode()).append(',')
                .append(p.getContentType() != null ? p.getContentType() : "")
                .append('\n'));

        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(data);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"session-" + sessionId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .contentLength(data.length)
                .body(resource);
    }
}
