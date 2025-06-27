# External Connection Configuration Fix

## Problem Identified

The original Redis and Kafka configurations were **NOT** set up to accept external connections from worker nodes on different machines.

## Issues Found

### 1. Redis Configuration
**Problem:** Redis was using default configuration which only accepts localhost connections
```yaml
# BEFORE (incorrect)
command: redis-server --appendonly yes
```

**Solution:** Added bind to all interfaces and disabled protected mode
```yaml
# AFTER (correct)
command: redis-server --appendonly yes --bind 0.0.0.0 --protected-mode no
```

### 2. Kafka Configuration
**Problem:** Kafka was advertising localhost and incorrect port combinations
```yaml
# BEFORE (incorrect)
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,PLAINTEXT_HOST://172.16.130.42:9093
```

**Solution:** Simplified to advertise only the external IP on the correct port
```yaml
# AFTER (correct)
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://172.16.130.42:9092
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092
```

### 3. MongoDB Configuration
**Status:** MongoDB was already correctly configured to accept external connections.

## Changes Made

### 1. Updated `docker-compose-services.yml`
- **Redis:** Added `--bind 0.0.0.0 --protected-mode no` to accept external connections
- **Kafka:** Fixed advertised listeners to use actual IP address
- **Kafka:** Added explicit listeners configuration

### 2. Updated `setup-master.bat`
- Added service restart to ensure new configuration is applied
- Added informational message about external connection support

### 3. Created `fix-external-connections.bat`
- Standalone script to fix existing deployments
- Automatically detects master IP and updates configuration
- Restarts services with correct settings
- Tests service accessibility

## Security Considerations

### Redis Security
- **`--protected-mode no`:** Disables Redis protected mode to allow external connections
- **Network Security:** Redis is exposed on port 6379 - ensure proper firewall rules
- **Recommendation:** In production, consider Redis AUTH or network-level security

### Kafka Security
- **Port 9092:** Exposed for external connections - ensure firewall allows this port
- **Authentication:** Current setup has no authentication - consider SASL for production

### MongoDB Security
- **Port 27017:** Already exposed - ensure firewall configuration
- **Authentication:** Current setup has no authentication - consider enabling auth for production

## How to Apply the Fix

### For New Deployments
1. Use the updated `setup-master.bat` script
2. Services will be configured correctly from the start

### For Existing Deployments
1. Run `fix-external-connections.bat` on the master node
2. This will restart services with the correct configuration

### Manual Fix (if needed)
1. Stop services: `docker-compose -f docker-compose-services.yml down`
2. Update the configuration files as shown above
3. Start services: `docker-compose -f docker-compose-services.yml up -d`

## Testing External Connections

From worker machines, test connectivity:

### Redis Test
```cmd
redis-cli -h <MASTER_IP> ping
# Should return: PONG
```

### MongoDB Test
```cmd
mongosh "mongodb://<MASTER_IP>:27017/webcrawler" --eval "db.runCommand('ping')"
# Should return: { ok: 1 }
```

### Kafka Test
```cmd
kafka-topics --bootstrap-server <MASTER_IP>:9092 --list
# Should return list of topics (may be empty initially)
```

### Network Connectivity Test
```cmd
test-connectivity.bat
# Follow prompts to test all services
```

## Expected Results

After applying the fix:
- ✅ Worker nodes can connect to Redis on the master
- ✅ Worker nodes can connect to MongoDB on the master  
- ✅ Worker nodes can connect to Kafka on the master
- ✅ Distributed crawling works across multiple machines
- ✅ No more "connection refused" errors in worker logs

## Firewall Requirements

Ensure these ports are open on the master node:
- **6379** - Redis
- **27017** - MongoDB
- **9092** - Kafka
- **8080** - Web UI (optional)

## Next Steps

1. Run `fix-external-connections.bat` on the master node
2. Wait for services to restart (about 30 seconds)
3. Test connectivity from worker nodes using `test-connectivity.bat`
4. Start worker nodes using `setup-worker.bat`
5. Verify distributed crawling in the web UI at `http://<MASTER_IP>:8080`
