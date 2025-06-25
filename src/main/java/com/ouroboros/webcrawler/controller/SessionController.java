package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Controller handling non-API session requests from the web UI
 */
@Controller
@RequestMapping("/sessions")
@Slf4j
public class SessionController {

    @Autowired
    private CrawlerManager crawlerManager;

    /**
     * Show details for a specific session
     */
    @GetMapping("/{sessionId}")
    public String sessionDetails(@PathVariable String sessionId, Model model) {
        log.info("WEB - Viewing details for session: {}", sessionId);

        Optional<CrawlSession> sessionOpt = crawlerManager.getSession(sessionId);
        if (sessionOpt.isPresent()) {
            model.addAttribute("session", sessionOpt.get());
            model.addAttribute("stats", crawlerManager.getSessionStats(sessionId));
            return "session-details";
        } else {
            // Session not found - redirect to the sessions list
            return "redirect:/sessions";
        }
    }

    /**
     * Pause a session - matches URL used by the frontend JavaScript
     */
    @PostMapping("/{sessionId}/pause")
    public ResponseEntity<?> pauseSession(@PathVariable String sessionId) {
        log.info("WEB - Pausing session with ID: {}", sessionId);

        try {
            crawlerManager.pauseSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to pause session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.badRequest().body("Failed to pause session");
        }
    }

    /**
     * Resume a session - matches URL used by the frontend JavaScript
     */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<?> resumeSession(@PathVariable String sessionId) {
        log.info("WEB - Resuming session with ID: {}", sessionId);

        try {
            crawlerManager.resumeSession(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to resume session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.badRequest().body("Failed to resume session");
        }
    }

    /**
     * Delete a session - handles the form submission from the delete modal
     */
    @PostMapping("/delete")
    public String deleteSession(@RequestParam String id) {
        log.info("WEB - Deleting session with ID: {}", id);

        try {
            crawlerManager.deleteSession(id);
        } catch (Exception e) {
            log.error("Failed to delete session {}: {}", id, e.getMessage(), e);
        }

        return "redirect:/sessions";
    }
}
