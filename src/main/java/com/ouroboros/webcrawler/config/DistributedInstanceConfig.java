package com.ouroboros.webcrawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    public String getMachineId() {
        if (machineId == null || machineId.trim().isEmpty()) {
            machineId = generateMachineId();
        }
        return machineId;
    }

    public String getAdvertisedHost() {
        return advertisedHost;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    private String generateMachineId() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isLoopback() && ni.isUp() && ni.getHardwareAddress() != null) {
                    byte[] mac = ni.getHardwareAddress();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) {
                            sb.append("-");
                        }
                    }
                    return "machine-" + sb.toString();
                }
            }
        } catch (SocketException e) {
            // Fall back to hostname-based ID
            return "machine-" + System.getProperty("user.name") + "-" + System.currentTimeMillis();
        }
        return "machine-" + System.currentTimeMillis();
    }
}
