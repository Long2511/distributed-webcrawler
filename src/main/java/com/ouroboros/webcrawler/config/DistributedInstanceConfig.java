package com.ouroboros.webcrawler.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Configuration for distributed crawler instances coordination
 */
@Configuration
@Slf4j
public class DistributedInstanceConfig {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${server.port}")
    private int serverPort;

    @Value("${webcrawler.instance.machine-id:}")
    private String machineId;

    @Value("${webcrawler.instance.advertised-host:}")
    private String advertisedHost;

    @Value("${webcrawler.instance.heartbeat-interval-seconds:30}")
    private int heartbeatIntervalSeconds;

    private final String instanceId = UUID.randomUUID().toString();
    private final String INSTANCE_KEY_PREFIX = "webcrawler:instances:";
    private final String QUEUE_KEY = "webcrawler:queue";
    private final String MACHINE_KEY_PREFIX = "webcrawler:machines:";

    @PostConstruct
    public void init() {
        // Generate machine ID if not set
        if (machineId == null || machineId.isEmpty()) {
            machineId = generateMachineId();
            log.info("Generated machine ID: {}", machineId);
        }

        log.info("Initializing crawler instance with ID: {} on machine: {}", instanceId, machineId);
        registerInstance();
        registerMachine();
        joinQueue();

        // Initial logging of the crawler network
        logCrawlerNetwork();
    }

    /**
     * Generate a unique machine ID based on network interfaces
     */
    private String generateMachineId() {
        try {
            // Try to use the MAC address of the first non-loopback interface
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isLoopback() && networkInterface.getHardwareAddress() != null) {
                    byte[] mac = networkInterface.getHardwareAddress();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) {
                            sb.append("-");
                        }
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to generate machine ID from network interfaces: {}", e.getMessage());
        }

        // Fallback to a random UUID if we can't get MAC address
        return "machine-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Get the unique ID for this crawler instance
     */
    @Bean
    public String instanceId() {
        return instanceId;
    }

    /**
     * Get the machine ID for this crawler
     */
    @Bean
    public String machineId() {
        return machineId;
    }

    /**
     * Register this instance in Redis with TTL
     */
    private void registerInstance() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String address = determineHostAddress();

            String instanceKey = INSTANCE_KEY_PREFIX + instanceId;
            String instanceInfo = String.format("%s:%d:%s:%s", address, serverPort, hostname, machineId);

            redisTemplate.opsForValue().set(instanceKey, instanceInfo);
            redisTemplate.expire(instanceKey, heartbeatIntervalSeconds * 2L, TimeUnit.SECONDS);

            log.info("Registered crawler instance: {} at {}:{} on machine: {}",
                     instanceId, address, serverPort, machineId);
        } catch (Exception e) {
            log.error("Failed to register instance: {}", e.getMessage(), e);
        }
    }

    /**
     * Determine the host address to advertise
     */
    private String determineHostAddress() {
        // Use configured advertised host if available
        if (advertisedHost != null && !advertisedHost.isEmpty()) {
            return advertisedHost;
        }

        try {
            // Try to find a non-loopback address
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isLoopback() && networkInterface.isUp()) {
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        // Prefer IPv4 addresses that are not link-local or loopback
                        if (!address.isLinkLocalAddress() && !address.isLoopbackAddress() &&
                            address.getHostAddress().indexOf(':') < 0) {
                            return address.getHostAddress();
                        }
                    }
                }
            }

            // Fallback to the local host address
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("Failed to determine host address: {}", e.getMessage());
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ex) {
                return "127.0.0.1"; // Last resort fallback
            }
        }
    }

    /**
     * Register this machine in Redis
     */
    private void registerMachine() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String machineKey = MACHINE_KEY_PREFIX + machineId;

            // Get current system metrics
            Runtime runtime = Runtime.getRuntime();
            int availableProcessors = runtime.availableProcessors();
            long maxMemory = runtime.maxMemory() / (1024 * 1024); // Convert to MB
            long freeMemory = runtime.freeMemory() / (1024 * 1024); // Convert to MB

            String machineInfo = String.format("%s:%s:%d:%d:%d",
                                              machineId, hostname, availableProcessors, maxMemory, freeMemory);

            redisTemplate.opsForValue().set(machineKey, machineInfo);
            redisTemplate.expire(machineKey, heartbeatIntervalSeconds * 2L, TimeUnit.SECONDS);

            log.info("Registered machine: {} ({}) with {} processors and {} MB max memory",
                     machineId, hostname, availableProcessors, maxMemory);
        } catch (Exception e) {
            log.error("Failed to register machine: {}", e.getMessage(), e);
        }
    }

    /**
     * Add this instance to processing queue
     */
    private void joinQueue() {
        try {
            // Add instance to the queue with score based on current time
            // This enables fair distribution based on join time
            redisTemplate.opsForZSet().add(QUEUE_KEY, instanceId, System.currentTimeMillis());
            log.info("Joined crawler queue with instance ID: {}", instanceId);
        } catch (Exception e) {
            log.error("Failed to join queue: {}", e.getMessage(), e);
        }
    }

    /**
     * Periodically refresh this instance registration in Redis
     */
    @Scheduled(fixedDelayString = "${webcrawler.instance.heartbeat-interval-seconds:30}000")
    public void refreshInstanceRegistration() {
        registerInstance();
        registerMachine();

        // Refresh queue position
        redisTemplate.opsForZSet().add(QUEUE_KEY, instanceId, System.currentTimeMillis());

        // Log active instances and machines
        logCrawlerNetwork();
    }

    /**
     * Get all active crawler instances
     */
    public Set<String> getActiveInstances() {
        return redisTemplate.keys(INSTANCE_KEY_PREFIX + "*");
    }

    /**
     * Get the number of active instances
     */
    public int getActiveInstanceCount() {
        return getActiveInstances().size();
    }

    /**
     * Get all instances in the processing queue
     */
    public List<String> getQueuedInstances() {
        Set<String> allMembers = redisTemplate.opsForZSet().range(QUEUE_KEY, 0, -1);
        if (allMembers == null) {
            return List.of();
        }
        return allMembers.stream().collect(Collectors.toList());
    }

    /**
     * Get all registered machines
     */
    public Set<String> getActiveMachines() {
        return redisTemplate.keys(MACHINE_KEY_PREFIX + "*");
    }

    /**
     * Get the number of active machines
     */
    public int getActiveMachineCount() {
        return getActiveMachines().size();
    }

    /**
     * Get instance information by instance ID
     */
    public String getInstanceInfo(String instanceId) {
        return redisTemplate.opsForValue().get(INSTANCE_KEY_PREFIX + instanceId);
    }

    /**
     * Get machine information by machine ID
     */
    public String getMachineInfo(String machineId) {
        return redisTemplate.opsForValue().get(MACHINE_KEY_PREFIX + machineId);
    }

    /**
     * Check if this instance should process a URL based on its hash
     * This enables distributed URL processing across instances
     *
     * @param url URL to be processed
     * @return true if this instance should process the URL
     */
    public boolean shouldProcessUrl(String url) {
        List<String> instances = getQueuedInstances();
        if (instances.isEmpty()) {
            // If no instances in queue, process all URLs
            return true;
        }

        // Find position of this instance in the queue
        int instancePosition = instances.indexOf(instanceId);
        if (instancePosition == -1) {
            // If this instance is not in queue (shouldn't happen),
            // add it back and process the URL
            joinQueue();
            return true;
        }

        // Distribute URLs based on hash
        int instanceCount = instances.size();
        int urlHash = Math.abs(url.hashCode() % instanceCount);

        // Return true if this instance should process this URL
        return urlHash == instancePosition;
    }

    /**
     * Log all active instances and machines
     */
    private void logCrawlerNetwork() {
        Set<String> instanceKeys = getActiveInstances();
        Set<String> machineKeys = getActiveMachines();

        log.info("Crawler network status: {} instances on {} machines (current instance: {})",
                instanceKeys.size(), machineKeys.size(), instanceId);

        if (log.isDebugEnabled()) {
            log.debug("Instances in processing queue: {}", getQueuedInstances().size());

            // Log detailed information about each machine and its instances
            try {
                // Group instances by machine
                Map<String, List<String>> instancesByMachine = Collections.emptyMap();

                for (String instanceKey : instanceKeys) {
                    String key = instanceKey.substring(INSTANCE_KEY_PREFIX.length());
                    String info = redisTemplate.opsForValue().get(instanceKey);
                    if (info != null && info.contains(":")) {
                        String[] parts = info.split(":");
                        if (parts.length >= 4) {
                            String macId = parts[3];
                            instancesByMachine.computeIfAbsent(macId, k -> new java.util.ArrayList<>()).add(key);
                        }
                    }
                }

                // Log each machine and its instances
                for (String machineKey : machineKeys) {
                    String macId = machineKey.substring(MACHINE_KEY_PREFIX.length());
                    String machineInfo = redisTemplate.opsForValue().get(machineKey);
                    List<String> machineInstances = instancesByMachine.getOrDefault(macId, Collections.emptyList());

                    log.debug("Machine {}: {} - {} instances",
                              macId, machineInfo, machineInstances.size());

                    for (String inst : machineInstances) {
                        log.debug("  Instance {}: {}", inst, redisTemplate.opsForValue().get(INSTANCE_KEY_PREFIX + inst));
                    }
                }
            } catch (Exception e) {
                log.warn("Error logging crawler network details: {}", e.getMessage());
            }
        }
    }

    /**
     * Clean up on application shutdown
     */
    @Bean(destroyMethod = "cleanup")
    public Object cleanup() {
        return new Object() {
            public void cleanup() {
                try {
                    // Remove instance from queue
                    redisTemplate.opsForZSet().remove(QUEUE_KEY, instanceId);
                    // Delete instance key
                    redisTemplate.delete(INSTANCE_KEY_PREFIX + instanceId);

                    // Check if this is the last instance on this machine
                    boolean lastInstanceOnMachine = true;
                    for (String instanceKey : getActiveInstances()) {
                        String info = redisTemplate.opsForValue().get(instanceKey);
                        if (info != null && info.contains(machineId) &&
                            !instanceKey.equals(INSTANCE_KEY_PREFIX + instanceId)) {
                            lastInstanceOnMachine = false;
                            break;
                        }
                    }

                    // If this is the last instance, also remove the machine registration
                    if (lastInstanceOnMachine) {
                        redisTemplate.delete(MACHINE_KEY_PREFIX + machineId);
                        log.info("Removed machine {} from registry (last instance shutting down)", machineId);
                    }

                    log.info("Removed instance {} from crawler queue and registry", instanceId);
                } catch (Exception e) {
                    log.error("Error during cleanup: {}", e.getMessage());
                }
            }
        };
    }
}
