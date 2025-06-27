package com.ouroboros.webcrawler.frontier;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FrontierStats {

    private long pendingUrls;
    private long completedUrls;
    private long failedUrls;
    private long totalDiscovered;
    private double averageDepth;
    private long bytesTransferred;
    private double crawlRate; // pages per second
}
