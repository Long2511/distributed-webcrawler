# DISTRIBUTED SETUP TROUBLESHOOTING GUIDE

## Problem: Connection Refused / Cannot Connect to Services

### Root Cause
The logs show that machines are trying to connect to `localhost` instead of the actual master node IP address. This is a common issue in distributed setups.

### Symptoms
- `Connection refused: /127.0.0.1:9092` (Kafka)
- `Connection refused: /127.0.0.1:27017` (MongoDB) 
- `UnknownHostException: localhost: nodename nor servname provided`
- Workers cannot join the cluster

### Solution

#### Step 1: Update Configuration Files

The configuration files have been updated to use the correct IP addresses:

**Master Node (172.16.130.42):**
- Use `config/master-node.properties`
- All services now point to `172.16.130.42` instead of `localhost`

**Worker Nodes:**
- Use `config/worker-node.properties` 
- All services point to master IP `172.16.130.42`
- Worker advertised host set to `172.16.130.41` (update for each worker)

#### Step 2: Update Kafka Configuration

**CRITICAL:** Kafka's `server.properties` must advertise the correct IP address.

Run the Kafka configuration update script:

**Windows:**
```cmd
update-kafka-config.bat
```

**Linux:**
```bash
chmod +x update-kafka-config.sh
./update-kafka-config.sh
```

Or manually edit Kafka's `server.properties`:
```properties
listeners=PLAINTEXT://0.0.0.0:9092
advertised.listeners=PLAINTEXT://172.16.130.42:9092
```

#### Step 3: Verify Network Connectivity

Before starting the applications, test connectivity from worker machines:

**Test Kafka:**
```bash
telnet 172.16.130.42 9092
```

**Test MongoDB:**
```bash
telnet 172.16.130.42 27017
```

**Test Redis:**
```bash
telnet 172.16.130.42 6379
```

**Test Web UI (optional):**
```bash
curl http://172.16.130.42:8080/health
```

#### Step 4: Start Services in Correct Order

**On Master Node (172.16.130.42):**
1. Start MongoDB, Redis, Kafka
2. Wait for all services to be ready
3. Start the web crawler application:
   ```cmd
   java -jar target/webcrawler-1.0-SNAPSHOT.jar --spring.config.location=config/master-node.properties
   ```

**On Worker Nodes:**
1. Verify connectivity to master services (Step 3)
2. Update `worker-node.properties` with correct IPs for this worker:
   ```properties
   webcrawler.instance.advertised-host=<THIS_WORKER_IP>
   ```
3. Start the worker application:
   ```cmd
   java -jar target/webcrawler-1.0-SNAPSHOT.jar --spring.config.location=config/worker-node.properties
   ```

### Configuration File Summary

#### master-node.properties
```properties
# Services bind to master IP
spring.data.mongodb.host=172.16.130.42
spring.redis.host=172.16.130.42
spring.kafka.bootstrap-servers=172.16.130.42:9092

# Master advertises its IP
webcrawler.instance.advertised-host=172.16.130.42
webcrawler.instance.is-master=true
```

#### worker-node.properties  
```properties
# Connect to master services
spring.data.mongodb.uri=mongodb://172.16.130.42:27017/webcrawler
spring.redis.host=172.16.130.42
spring.kafka.bootstrap-servers=172.16.130.42:9092

# Worker advertises its own IP (update for each worker)
webcrawler.instance.advertised-host=172.16.130.41
webcrawler.instance.is-master=false
```

### Network Requirements

1. **Port Accessibility:** Master node ports must be accessible from worker nodes:
   - 9092 (Kafka)
   - 27017 (MongoDB)
   - 6379 (Redis)
   - 8080 (Web UI, optional)

2. **Firewall:** Ensure firewall allows connections on these ports

3. **DNS/Hosts:** If using hostnames instead of IPs, ensure all machines can resolve them

### Verification Steps

1. **Check Master Node Logs:**
   - Should see worker registrations
   - No connection errors
   - Services starting successfully

2. **Check Worker Node Logs:**
   - Successful connections to all services
   - Registration with master
   - Ready to receive crawl tasks

3. **Web UI:** Access `http://172.16.130.42:8080/dashboard` to see cluster status

4. **Test Crawling:** Start a crawl session and verify both master and worker are processing URLs

### Common Issues

1. **IP Address Mismatch:** Double-check all IP addresses in configuration files
2. **Service Not Started:** Ensure MongoDB, Redis, Kafka are running before starting applications
3. **Port Blocked:** Check firewall/network security groups
4. **Kafka Advertised Listeners:** Most critical - Kafka must advertise reachable IP address
5. **Configuration File Location:** Ensure using correct config file with `--spring.config.location`

### Testing the Fix

After applying these changes:
1. Restart all services on master node
2. Restart applications on all nodes
3. Check logs for successful connections
4. Verify worker registration in master logs
5. Test distributed crawling functionality
