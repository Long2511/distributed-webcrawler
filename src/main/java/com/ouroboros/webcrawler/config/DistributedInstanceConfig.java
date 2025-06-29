package com.ouroboros.webcrawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

@Data
@Component
@ConfigurationProperties(prefix = "webcrawler.instance")
public class DistributedInstanceConfig {

    private String machineId;
    private String advertisedHost;
    private int heartbeatIntervalSeconds = 30;

    @Value("${server.port:8080}")
    private int serverPort;

    public String getMachineId() {
        if (machineId == null || machineId.trim().isEmpty()) {
            machineId = generateMachineId();
        }
        return machineId;
    }

    public String getAdvertisedHost() {
        return advertisedHost;
    }

    public String getWorkerAddress() {
        if (advertisedHost != null && !advertisedHost.trim().isEmpty()) {
            return advertisedHost + ":" + serverPort;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress() + ":" + serverPort;
        } catch (Exception e) {
            return "localhost:" + serverPort;
        }
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    private String generateMachineId() {
        try {
            // Generate unique worker ID: MAC_ADDRESS-PORT-TIMESTAMP
            String macAddress = getMacAddress();
            String timestamp = String.valueOf(System.currentTimeMillis() % 100000); // Last 5 digits
            return String.format("%s-%d-%s", macAddress, serverPort, timestamp);
        } catch (Exception e) {
            // Fallback: hostname-port-random
            try {
                String hostname = InetAddress.getLocalHost().getHostName();
                String random = String.valueOf(System.currentTimeMillis() % 10000);
                return String.format("%s-%d-%s", hostname, serverPort, random);
            } catch (Exception ex) {
                // Ultimate fallback: random ID
                return "worker-" + serverPort + "-" + System.currentTimeMillis();
            }
        }
    }

    private String getMacAddress() {
        try {
            NetworkInterface network = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            if (network != null) {
                byte[] mac = network.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            // Fall back to hostname
        }

        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
