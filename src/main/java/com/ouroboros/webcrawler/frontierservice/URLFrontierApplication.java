package com.ouroboros.webcrawler.frontierservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * URLFrontier Microservice Application
 * Handles URL queuing, prioritization, and assignment to workers
 */
@SpringBootApplication
@EnableScheduling
@ConditionalOnProperty(name = "webcrawler.service.type", havingValue = "urlfrontier")
public class URLFrontierApplication {

    public static void main(String[] args) {
        SpringApplication.run(URLFrontierApplication.class, args);
    }
}
