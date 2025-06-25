package com.ouroboros.webcrawler.controller;

import com.ouroboros.webcrawler.frontier.FrontierStats;
import com.ouroboros.webcrawler.frontier.URLFrontier;
import com.ouroboros.webcrawler.manager.CrawlerManager;
import com.ouroboros.webcrawler.model.CrawlSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.*;

@Controller
public class DashboardController {

    @Autowired
    private CrawlerManager crawlerManager;

    @Autowired
    private URLFrontier urlFrontier;

    /**
     * Dashboard home page
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        FrontierStats stats = urlFrontier.getStats();
        model.addAttribute("stats", stats);
        return "dashboard";
    }

    /**
     * List all crawl sessions
     */
    @GetMapping("/sessions")
    public String listSessions(Model model) {
        List<CrawlSession> sessions = crawlerManager.getAllSessions();
        model.addAttribute("sessions", sessions);
        return "sessions";
    }

    /**
     * Create new crawl session form
     */
    @GetMapping("/sessions/new")
    public String newSessionForm(Model model) {
        model.addAttribute("session", new CrawlSession());
        return "session-form";
    }

    /**
     * Create new crawl session
     */
    @PostMapping("/sessions")
    public String createSession(@ModelAttribute CrawlSession session, RedirectAttributes redirectAttributes) {
        // Ensure seedUrls is not null
        if (session.getSeedUrls() == null || session.getSeedUrls().isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "At least one seed URL is required");
            return "redirect:/sessions/new";
        }

        CrawlSession createdSession = crawlerManager.startCrawlSession(session);
        redirectAttributes.addFlashAttribute("success",
                "Crawl session started with " + createdSession.getSeedUrls().size() + " seed URLs");
        return "redirect:/sessions/" + createdSession.getId();
    }

    /**
     * View session details
     */
    @GetMapping("/sessions/{id}")
    public String sessionDetails(@PathVariable("id") String sessionId, Model model) {
        Optional<CrawlSession> sessionOpt = crawlerManager.getSession(sessionId);

        if (sessionOpt.isEmpty()) {
            model.addAttribute("error", "Session not found");
            return "redirect:/sessions";
        }

        CrawlSession session = sessionOpt.get();
        Map<String, Long> stats = crawlerManager.getSessionStats(sessionId);

        model.addAttribute("session", session);
        model.addAttribute("stats", stats);

        return "session-details";
    }

    /**
     * Stop a crawl session
     */
    @PostMapping("/sessions/{id}/stop")
    public String stopSession(@PathVariable("id") String sessionId, RedirectAttributes redirectAttributes) {
        CrawlSession stoppedSession = crawlerManager.stopCrawlSession(sessionId);

        if (stoppedSession != null) {
            redirectAttributes.addFlashAttribute("success", "Crawl session stopped");
        } else {
            redirectAttributes.addFlashAttribute("error", "Session not found or already stopped");
        }

        return "redirect:/sessions/" + sessionId;
    }

    /**
     * Handle quick crawl form submission
     */
    @PostMapping("/crawl/start")
    public String startQuickCrawl(
            @RequestParam("seedUrl") String seedUrl,
            @RequestParam("maxDepth") int maxDepth,
            @RequestParam(value = "respectRobotsTxt", defaultValue = "true") boolean respectRobotsTxt,
            RedirectAttributes redirectAttributes) {

        // Create a new crawl session with the form data
        CrawlSession session = CrawlSession.builder()
                .name("Quick Crawl: " + seedUrl)
                .seedUrls(Set.of(seedUrl))
                .maxDepth(maxDepth)
                .respectRobotsTxt(respectRobotsTxt)
                .build();

        // Start the crawl session
        CrawlSession startedSession = crawlerManager.startCrawlSession(session);

        // Add a flash message to be displayed after redirect
        redirectAttributes.addFlashAttribute("message",
                "Crawl started for: " + seedUrl + " with max depth: " + maxDepth);

        return "redirect:/";
    }

    /**
     * View the real-time monitor page
     */
    @GetMapping("/monitor")
    public String monitor(Model model) {
        // Add any necessary data for the monitor page
        model.addAttribute("activeSessions", crawlerManager.getActiveSessions().size());
        return "monitor";
    }

    /**
     * Handle direct access to crawl/start page
     */
    @GetMapping("/crawl/start")
    public String crawlStartForm(Model model) {
        // Redirect to dashboard which contains the form
        return "redirect:/";
    }

    /**
     * Get the latest log entries
     *
     * @param lines Number of lines to retrieve
     * @return List of log entries
     */
    @GetMapping("/api/logs")
    @ResponseBody
    public List<Map<String, String>> getLogEntries(@RequestParam(defaultValue = "50") int lines) {
        List<Map<String, String>> logEntries = new ArrayList<>();
        File logFile = new File("logs/webcrawler.log");

        if (logFile.exists()) {
            try {
                List<String> logLines = readLastLines(logFile, lines);

                for (String line : logLines) {
                    Map<String, String> entry = new HashMap<>();

                    // Parse timestamp
                    String timestamp = "";
                    if (line.length() > 19) {
                        timestamp = line.substring(0, 19);
                        line = line.substring(20);
                    }

                    // Determine log level
                    String level = "INFO";
                    if (line.contains(" ERROR ")) level = "ERROR";
                    else if (line.contains(" WARN ")) level = "WARNING";
                    else if (line.contains(" DEBUG ")) level = "DEBUG";

                    // Extract message
                    String message = line;
                    if (message.contains(" - ")) {
                        message = message.substring(message.indexOf(" - ") + 3);
                    }

                    entry.put("timestamp", timestamp);
                    entry.put("level", level);
                    entry.put("message", message);

                    logEntries.add(entry);
                }

                // Reverse to show newest first
                Collections.reverse(logEntries);

            } catch (IOException e) {
                Map<String, String> errorEntry = new HashMap<>();
                errorEntry.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                errorEntry.put("level", "ERROR");
                errorEntry.put("message", "Error reading log file: " + e.getMessage());
                logEntries.add(errorEntry);
            }
        } else {
            Map<String, String> errorEntry = new HashMap<>();
            errorEntry.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            errorEntry.put("level", "WARNING");
            errorEntry.put("message", "Log file not found at: " + logFile.getAbsolutePath());
            logEntries.add(errorEntry);
        }

        return logEntries;
    }

    /**
     * Helper method to read last N lines from a file
     */
    private List<String> readLastLines(File file, int lines) throws IOException {
        LinkedList<String> result = new LinkedList<>();
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            long fileLength = file.length() - 1;
            randomAccessFile.seek(fileLength);

            for (int i = 0; i < lines; i++) {
                StringBuilder sb = new StringBuilder();
                long pointer = fileLength;

                // Read the file backwards until we hit a newline or the beginning of the file
                while (pointer >= 0) {
                    randomAccessFile.seek(pointer);
                    char c = (char) randomAccessFile.read();

                    if (c == '\n' && sb.length() > 0) {
                        break;
                    } else if (c != '\n' && c != '\r') {
                        sb.insert(0, c);
                    }

                    pointer--;

                    // If we've reached the beginning of the file
                    if (pointer < 0 && sb.length() > 0) {
                        break;
                    }
                }

                if (sb.length() > 0) {
                    result.addFirst(sb.toString());
                    fileLength = pointer;
                } else {
                    break; // No more lines to read
                }
            }
        }
        return result;
    }
}
