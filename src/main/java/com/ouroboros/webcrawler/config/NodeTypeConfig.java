package com.ouroboros.webcrawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

/**
 * Configuration for enabling/disabling components based on node type
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "webcrawler")
public class NodeTypeConfig {

    private String mode = "standalone"; // master, worker, standalone

    private EnableConfig enable = new EnableConfig();

    @Data
    public static class EnableConfig {
        private boolean webUi = true;
        private boolean sessionManagement = true;
        private boolean worker = true;
        private boolean coordinator = true;
    }

    // Helper methods
    public boolean isMasterNode() {
        return "master".equals(mode);
    }

    public boolean isWorkerNode() {
        return "worker".equals(mode);
    }

    public boolean isStandaloneNode() {
        return "standalone".equals(mode);
    }
}
