@echo off
REM Distributed Web Crawler - Worker Node Setup Script (Windows)
REM This script sets up a worker node that connects to an existing master with proper Kafka networking

echo =================================================================
echo   DISTRIBUTED WEB CRAWLER - WORKER NODE SETUP
echo =================================================================
echo.
echo This script sets up a worker node that connects to an existing master.
echo.
echo PREREQUISITES:
echo   1. Master node must be running (setup-master.bat)
echo   2. You need the Master Node IP address
echo   3. Network connectivity to master node
echo.

REM =====================
REM 1. Get Master IP from user
REM =====================
echo [1/8] Getting master node information...
set /p MASTER_IP=Enter the Master Node IP address: 
if "%MASTER_IP%"=="" (
    echo ERROR: Master IP address is required.
    pause
    exit /b 1
)

echo.
echo Connecting to master node at: %MASTER_IP%
echo    - Redis:    %MASTER_IP%:6379
echo    - MongoDB:  %MASTER_IP%:27017  
echo    - Kafka:    %MASTER_IP%:19092 (external access)
echo.

REM =====================
REM 2. Test connectivity to master services
REM =====================
echo Testing connectivity to master services...
echo Testing Redis connection...
powershell -Command "try {$tcpClient = New-Object System.Net.Sockets.TcpClient; $tcpClient.ConnectAsync('%MASTER_IP%', 6379).Wait(3000); $tcpClient.Close(); echo 'Redis: OK'} catch {echo 'Redis: FAILED - Cannot connect to %MASTER_IP%:6379'}"

echo Testing MongoDB connection...
powershell -Command "try {$tcpClient = New-Object System.Net.Sockets.TcpClient; $tcpClient.ConnectAsync('%MASTER_IP%', 27017).Wait(3000); $tcpClient.Close(); echo 'MongoDB: OK'} catch {echo 'MongoDB: FAILED - Cannot connect to %MASTER_IP%:27017'}"

echo Testing Kafka connection...
powershell -Command "try {$tcpClient = New-Object System.Net.Sockets.TcpClient; $tcpClient.ConnectAsync('%MASTER_IP%', 19092).Wait(3000); $tcpClient.Close(); echo 'Kafka: OK'} catch {echo 'Kafka: FAILED - Cannot connect to %MASTER_IP%:19092'}"

echo.
echo If any services show FAILED, please:
echo   1. Ensure the master node is running (setup-master.bat)
echo   2. Check firewall settings on master machine
echo   3. Verify the master IP address is correct
echo.
pause

echo.
REM =====================
REM 3. Detect worker node IP
REM =====================
echo [2/8] Detecting worker node IP address...
powershell -Command "$ip = Get-NetIPAddress | Where-Object {$_.AddressFamily -eq 'IPv4' -and $_.PrefixOrigin -eq 'Dhcp'} | Select-Object -First 1 -ExpandProperty IPAddress; if ($ip) { Write-Output $ip } else { Write-Output '127.0.0.1' }" > temp_ip.txt
set /p WORKER_IP=<temp_ip.txt
del temp_ip.txt

if "%WORKER_IP%"=="127.0.0.1" (
    echo WARNING: Could not detect worker IP address automatically.
    set /p WORKER_IP=Please enter this machine's IP address manually: 
)

echo This worker's IP address: %WORKER_IP%
echo Worker will be accessible at: http://%WORKER_IP%:8081

REM =====================
REM 4. Create necessary directories
REM =====================
echo [3/8] Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config
echo Directories created.

REM =====================
REM 5. Create worker configuration
REM =====================
echo [4/8] Creating worker configuration...
echo # Worker Node Configuration > config\worker-node-local.properties
echo spring.application.name=distributed-webcrawler-worker >> config\worker-node-local.properties
echo server.port=8081 >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # MongoDB configuration (connect to master node) >> config\worker-node-local.properties
echo spring.data.mongodb.host=%MASTER_IP% >> config\worker-node-local.properties
echo spring.data.mongodb.port=27017 >> config\worker-node-local.properties
echo spring.data.mongodb.database=webcrawler >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Redis configuration (connect to master node) >> config\worker-node-local.properties
echo spring.redis.host=%MASTER_IP% >> config\worker-node-local.properties
echo spring.redis.port=6379 >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Kafka configuration (connect to master node) >> config\worker-node-local.properties
echo spring.kafka.bootstrap-servers=%MASTER_IP%:19092 >> config\worker-node-local.properties
echo spring.kafka.consumer.group-id=webcrawler-workers >> config\worker-node-local.properties
echo spring.kafka.consumer.auto-offset-reset=earliest >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Worker instance settings >> config\worker-node-local.properties
echo webcrawler.instance.is-master=false >> config\worker-node-local.properties
echo webcrawler.instance.enable-web-ui=false >> config\worker-node-local.properties
echo webcrawler.instance.advertised-host=%WORKER_IP% >> config\worker-node-local.properties
echo webcrawler.instance.heartbeat-interval-seconds=60 >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Web crawler settings >> config\worker-node-local.properties
echo webcrawler.user-agent=Ouroboros Web Crawler/1.0 (Worker Node) >> config\worker-node-local.properties
echo webcrawler.max-depth=10 >> config\worker-node-local.properties
echo webcrawler.politeness.delay=500 >> config\worker-node-local.properties
echo webcrawler.politeness.respect-robots-txt=true >> config\worker-node-local.properties
echo webcrawler.batch.size=15 >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Frontier settings >> config\worker-node-local.properties
echo webcrawler.frontier.batch-size=15 >> config\worker-node-local.properties
echo webcrawler.frontier.adaptive-allocation=true >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Kafka topics >> config\worker-node-local.properties
echo webcrawler.kafka.topics.crawl-tasks=webcrawler.tasks >> config\worker-node-local.properties
echo webcrawler.kafka.topics.partition-count=10 >> config\worker-node-local.properties
echo webcrawler.kafka.topics.replication-factor=1 >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Logging configuration >> config\worker-node-local.properties
echo logging.level.root=INFO >> config\worker-node-local.properties
echo logging.level.com.ouroboros.webcrawler=DEBUG >> config\worker-node-local.properties
echo logging.file.name=logs/webcrawler-worker.log >> config\worker-node-local.properties
echo logging.file.max-size=10MB >> config\worker-node-local.properties
echo logging.file.max-history=20 >> config\worker-node-local.properties
echo. >> config\worker-node-local.properties

echo # Actuator endpoints >> config\worker-node-local.properties
echo management.endpoints.web.exposure.include=health,info,metrics >> config\worker-node-local.properties
echo management.endpoint.health.show-details=always >> config\worker-node-local.properties

echo Configuration file created: config\worker-node-local.properties

echo =====================
REM 6. Build the web crawler application if needed
REM =====================
echo [5/8] Building web crawler application...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo ERROR: Build failed. Please check the build errors above.
    pause
    exit /b 1
)

echo =====================
REM 7. Start worker node
REM =====================
echo [6/8] Starting worker node...
start "WebCrawler Worker - %WORKER_IP%" java -jar target/webcrawler-1.0-SNAPSHOT.jar --spring.config.location=file:./config/worker-node-local.properties

echo Waiting for worker to initialize...
timeout /t 15 /nobreak >nul

echo Checking worker health...
powershell -Command "try {(Invoke-WebRequest -UseBasicParsing http://localhost:8081/actuator/health).StatusCode} catch {exit 1}" >nul 2>&1
if errorlevel 1 (
    echo Worker may still be starting up or failed. Check logs in logs/webcrawler-worker.log.
) else (
    echo SUCCESS: Worker started successfully and is healthy!
)

echo.
echo ================================================================
echo   WORKER NODE SETUP COMPLETE!
echo ================================================================
echo.
echo WORKER NODE INFORMATION:
echo   - Worker IP:     %WORKER_IP%:8081
echo   - Health Check:  http://%WORKER_IP%:8081/actuator/health
echo   - Log file:      logs/webcrawler-worker.log
echo.
echo MASTER NODE CONNECTIONS:
echo   - Master IP:     %MASTER_IP%
echo   - Redis:         %MASTER_IP%:6379
echo   - MongoDB:       %MASTER_IP%:27017
echo   - Kafka:         %MASTER_IP%:19092 (external access)
echo.
echo NEXT STEPS:
echo   1. Check the worker application window for startup messages
echo   2. Verify connection in Master UI: http://%MASTER_IP%:8080
echo   3. Monitor logs in: logs/webcrawler-worker.log
echo.
echo KAFKA NETWORKING NOTES:
echo   - Worker uses external Kafka access: %MASTER_IP%:19092
echo   - Master services use internal access: kafka:9092
echo   - This ensures proper network isolation and connectivity
echo ================================================================

echo.
echo Press any key to view the worker log file...
pause >nul

if exist logs\webcrawler-worker.log (
    start notepad logs\webcrawler-worker.log
) else (
    echo Log file not created yet. Worker may still be starting.
    echo Check the worker application window for startup progress.
)
