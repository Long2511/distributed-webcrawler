package com.ouroboros.webcrawler.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class DashboardController {

    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/monitor")
    public String monitor() {
        return "monitor";
    }

    @GetMapping("/sessions")
    public String sessions() {
        return "sessions";
    }

    @GetMapping("/sessions/{sessionId}")
    public String sessionDetails(@PathVariable String sessionId, Model model) {
        model.addAttribute("sessionId", sessionId);
        return "session-details";
    }
}
