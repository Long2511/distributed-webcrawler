package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;

@Controller
@Slf4j
public class DashboardController {

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private URLFrontier urlFrontier;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("activeSessions", crawlerManager.getActiveCrawlSessions());
        model.addAttribute("frontierStats", urlFrontier.getStats());
        return "dashboard";
    }

    @GetMapping("/sessions")
    public String listSessions(Model model) {
        model.addAttribute("sessions", crawlerManager.getAllCrawlSessions());
        return "sessions";
    }

    @GetMapping("/sessions/new")
    public String newSessionForm(Model model) {
        model.addAttribute("session", new CrawlSession());
        return "session-form";
    }

    @PostMapping("/sessions/new")
    public String createSession(@ModelAttribute CrawlSession session) {
        // Convert comma-separated seed URLs to set if needed
        if (session.getSeedUrls() == null) {
            session.setSeedUrls(new HashSet<>());
        }

        // Set default values if not provided
        if (session.getMaxDepth() <= 0) {
            session.setMaxDepth(5);
        }

        if (session.getMaxPagesPerDomain() <= 0) {
            session.setMaxPagesPerDomain(1000);
        }

        if (session.getCrawlDelay() < 0) {
            session.setCrawlDelay(500);
        }

        session.setRespectRobotsTxt(true);

        crawlerManager.startCrawlSession(session);
        return "redirect:/sessions";
    }

    @GetMapping("/sessions/{id}")
    public String viewSession(@PathVariable String id, Model model) {
        Optional<CrawlSession> session = crawlerManager.getCrawlSession(id);
        if (session.isPresent()) {
            model.addAttribute("session", session.get());
            return "session-details";
        } else {
            return "redirect:/sessions";
        }
    }

    @GetMapping("/sessions/{id}/pause")
    public String pauseSession(@PathVariable String id) {
        crawlerManager.pauseCrawlSession(id);
        return "redirect:/sessions/" + id;
    }

    @GetMapping("/sessions/{id}/resume")
    public String resumeSession(@PathVariable String id) {
        crawlerManager.resumeCrawlSession(id);
        return "redirect:/sessions/" + id;
    }

    @GetMapping("/sessions/{id}/stop")
    public String stopSession(@PathVariable String id) {
        crawlerManager.stopCrawlSession(id);
        return "redirect:/sessions/" + id;
    }

    @GetMapping("/monitor")
    public String monitorSystem(Model model) {
        model.addAttribute("frontierStats", urlFrontier.getStats());
        model.addAttribute("activeSessions", crawlerManager.getActiveCrawlSessions());
        model.addAttribute("totalSessions", crawlerManager.getAllCrawlSessions().size());
        return "monitor";
    }
}
