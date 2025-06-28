# Kafka Connectivity Troubleshooting Guide

## Common Issues and Solutions

### 1. "No such host is known (kafka)" Error

**Problem**: The application tries to connect to `kafka:9092` but can't resolve the hostname.

**Root Cause**: Master application is configured to use internal Kafka listener instead of external.

**Solution**: 
- Master applications should use port `19092` (external listener)
- Worker applications should use port `19092` (external listener)
- Only Docker containers should use `kafka:9092` (internal listener)

### 2. "Cluster ID doesn't match" Error

**Problem**: Kafka shows cluster ID mismatch error on startup.

**Root Cause**: Stale Kafka/Zookeeper data from previous runs.

**Solution**: 
```bash
# Clean up volumes and restart
docker-compose down
docker volume rm distributed-webcrawler_kafka_data distributed-webcrawler_zookeeper_data
docker-compose up -d
```

### 3. Configuration Quick Reference

#### Master Node Application (`master-node.properties`)
```properties
# ✅ CORRECT - Use external listener
spring.kafka.bootstrap-servers=YOUR_IP:19092

# ❌ INCORRECT - Don't use internal listener for host applications  
spring.kafka.bootstrap-servers=YOUR_IP:9092
```

#### Worker Node Application (`worker-node.properties`)
```properties
# ✅ CORRECT - Use external listener
spring.kafka.bootstrap-servers=MASTER_IP:19092

# ❌ INCORRECT - Don't use internal listener for host applications
spring.kafka.bootstrap-servers=MASTER_IP:9092
```

#### Docker Services (inside docker-compose.yml)
```yaml
# ✅ CORRECT - Containers use internal listener
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9092,EXTERNAL://YOUR_IP:19092
```

### 4. Port Reference

| Service | Internal (Container-to-Container) | External (Host-to-Container) |
|---------|-----------------------------------|------------------------------|
| Kafka   | kafka:9092                       | YOUR_IP:19092               |
| MongoDB | mongodb:27017                    | YOUR_IP:27017               |
| Redis   | redis:6379                       | YOUR_IP:6379                |

### 5. Health Check Commands

```bash
# Check if services are running
docker-compose ps

# Test Kafka connectivity
docker exec webcrawler-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Test Redis connectivity  
docker exec webcrawler-redis redis-cli ping

# Test MongoDB connectivity
docker exec webcrawler-mongodb mongo --eval "db.adminCommand('ismaster')"

# Check application logs
docker-compose logs kafka
```

### 6. Network Troubleshooting

```bash
# Check if ports are accessible from host
telnet YOUR_IP 19092  # Kafka external
telnet YOUR_IP 27017  # MongoDB
telnet YOUR_IP 6379   # Redis

# Check Docker networks
docker network ls
docker network inspect distributed-webcrawler_webcrawler-network
```

### 7. Common Fix Sequence

1. **Stop all services**:
   ```bash
   docker-compose down
   ```

2. **Clean volumes** (if cluster ID issues):
   ```bash
   docker volume rm distributed-webcrawler_kafka_data distributed-webcrawler_zookeeper_data
   ```

3. **Update configuration** to use port 19092 for external access

4. **Restart services**:
   ```bash
   docker-compose up -d
   ```

5. **Wait for startup** (45+ seconds for clean start)

6. **Restart application** with updated config

### 8. Prevention Tips

- Always use external listeners (port 19092) for host applications
- Use internal listeners (kafka:9092) only for containerized services
- Clean up volumes when changing cluster configurations
- Allow sufficient startup time for Kafka/Zookeeper initialization
- Verify IP addresses in configuration files match your actual network setup
