@echo off
REM Distributed Web Crawler - Master Node Setup Script (Windows)
REM This script sets up the master node with all central services including proper Kafka networking

REM =====================
REM 1. Detect Master IP using PowerShell (no wmic)
REM =====================
echo =================================================================
echo   DISTRIBUTED WEB CRAWLER - MASTER NODE SETUP
echo =================================================================
echo.
echo [1/7] Detecting master node IP address...

where powershell >nul 2>&1
if errorlevel 1 (
    echo ERROR: PowerShell is required but not found. Please install PowerShell and try again.
    pause
    exit /b 1
)

REM Try multiple methods to get IP address
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ip = $null; $adapters = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and ($_.Name -like '*Ethernet*' -or $_.Name -like '*Wi-Fi*') }; foreach ($adapter in $adapters) { $addr = Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }; if ($addr) { $ip = $addr.IPAddress; break; } }; if (-not $ip) { $ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1).IPAddress }; $ip" > "%TEMP%\master_ip.txt"
set /p MASTER_IP=<"%TEMP%\master_ip.txt"
del "%TEMP%\master_ip.txt"

if "%MASTER_IP%"=="" (
    echo WARNING: Could not detect IP address automatically.
    echo Available network adapters and their IP addresses:
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-NetAdapter | Where-Object { $_.Status -eq 'Up' } | ForEach-Object { $adapter = $_; Write-Host ('[' + $adapter.Name + ']'); Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 | ForEach-Object { Write-Host ('  ' + $_.IPAddress) } }"
    echo.
    set /p MASTER_IP=Please enter your machine's IP address manually: 
)

if "%MASTER_IP%"=="" (
    echo ERROR: No IP address provided. Cannot continue.
    pause
    exit /b 1
)

echo.
echo ================================
echo MASTER NODE IP ADDRESS: %MASTER_IP%
echo ================================
echo.
echo IMPORTANT: Workers must use this IP address to connect!
echo Share this IP with all worker machines: %MASTER_IP%
echo.

REM =====================
REM 2. Create Directories
REM =====================
echo [2/7] Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config
if not exist data mkdir data
echo Directories created.

REM =====================
REM 3. Update .env for Docker Compose
REM =====================
echo [3/7] Updating .env file for Docker Compose...
echo HOST_IP=%MASTER_IP% > .env
echo .env updated.

REM =====================
REM 4. Update docker-compose.yml for all services
REM =====================
echo [4/7] Updating docker-compose.yml for all services...
if exist docker-compose.yml (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$content = Get-Content 'docker-compose.yml' -Raw; $content = $content -replace 'EXTERNAL://\${HOST_IP:-localhost}:19092', 'EXTERNAL://%MASTER_IP%:19092' -replace 'localhost:6379', '%MASTER_IP%:6379' -replace '127.0.0.1:6379', '%MASTER_IP%:6379' -replace 'localhost:27017', '%MASTER_IP%:27017' -replace '127.0.0.1:27017', '%MASTER_IP%:27017' -replace 'localhost:2181', '%MASTER_IP%:2181' -replace '127.0.0.1:2181', '%MASTER_IP%:2181' -replace 'localhost', '%MASTER_IP%' -replace '127.0.0.1', '%MASTER_IP%' -replace '192.168.1.100', '%MASTER_IP%' -replace '172.16.128.246', '%MASTER_IP%'; $content | Out-File -FilePath 'docker-compose.yml' -Encoding UTF8"
    echo docker-compose.yml updated.
) else (
    echo WARNING: docker-compose.yml not found!
)

REM =====================
REM 5. Update all config files with detected IP
REM =====================
echo [5/7] Updating configuration files with detected IP: %MASTER_IP%
REM Update master-node.properties
if exist config\master-node.properties (
    echo Updating config\master-node.properties ...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$content = Get-Content 'config\master-node.properties' -Raw; $content = $content -replace 'localhost', '%MASTER_IP%' -replace '127.0.0.1', '%MASTER_IP%' -replace '192.168.1.100', '%MASTER_IP%' -replace '172.16.128.246', '%MASTER_IP%'; $content | Out-File -FilePath 'config\master-node.properties' -Encoding UTF8"
    echo config\master-node.properties updated.
) else (
    echo WARNING: config\master-node.properties not found!
)

REM Update worker-node.properties template if present
if exist config\worker-node.properties (
    echo Updating config\worker-node.properties ...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$content = Get-Content 'config\worker-node.properties' -Raw; $content = $content -replace 'localhost', '%MASTER_IP%' -replace '127.0.0.1', '%MASTER_IP%' -replace '192.168.1.100', '%MASTER_IP%' -replace '172.16.128.246', '%MASTER_IP%'; $content | Out-File -FilePath 'config\worker-node.properties' -Encoding UTF8"
    echo config\worker-node.properties updated.
)

REM Update worker-node-local.properties if present
if exist config\worker-node-local.properties (
    echo Updating config\worker-node-local.properties ...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$content = Get-Content 'config\worker-node-local.properties' -Raw; $content = $content -replace 'localhost', '%MASTER_IP%' -replace '127.0.0.1', '%MASTER_IP%' -replace '192.168.1.100', '%MASTER_IP%' -replace '172.16.128.246', '%MASTER_IP%'; $content | Out-File -FilePath 'config\worker-node-local.properties' -Encoding UTF8"
    echo config\worker-node-local.properties updated.
)

echo All configuration files updated with IP: %MASTER_IP%

echo.
REM =====================
REM 6. Stop and Start Services
REM =====================
echo [6/7] Stopping existing services...
docker-compose down >nul 2>&1

echo Cleaning up previous Kafka/Zookeeper data to prevent conflicts...
docker volume rm distributed-webcrawler_kafka_data distributed-webcrawler_zookeeper_data >nul 2>&1
echo Previous data cleaned.

echo Starting central services (MongoDB, Redis, Kafka, Zookeeper, Web UIs)...
docker-compose up -d

echo [7/7] Waiting for services to initialize...
echo This may take up to 30 seconds...
timeout /t 15 /nobreak >nul

echo Checking service health...
echo.
echo Redis status:
docker exec webcrawler-redis redis-cli ping 2>nul || echo Redis not ready yet

echo.
echo Kafka status:
docker ps --filter "name=webcrawler-kafka" --format "table {{.Names}}\t{{.Status}}" | findstr webcrawler-kafka

echo.
echo MongoDB status:
docker ps --filter "name=webcrawler-mongodb" --format "table {{.Names}}\t{{.Status}}" | findstr webcrawler-mongodb

echo [8/7] Building web crawler application...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo ERROR: Build failed. Please check the build errors above.
    pause
    exit /b 1
)

echo Starting master node application...
start "WebCrawler Master" java -Dspring.config.location=config/master-node.properties -jar target/webcrawler-1.0-SNAPSHOT.jar

echo.
echo ================================================================
echo   MASTER NODE SETUP COMPLETE!
echo ================================================================
echo.
echo ACCESS POINTS:
echo   - Web Crawler Dashboard: http://%MASTER_IP%:8080
echo   - Redis Web UI:          http://%MASTER_IP%:8082
echo   - MongoDB Web UI:        http://%MASTER_IP%:8083 (admin/pass)
echo.
echo SERVICE ENDPOINTS:
echo   - MongoDB:  %MASTER_IP%:27017
echo   - Redis:    %MASTER_IP%:6379  
echo   - Kafka:    %MASTER_IP%:19092 (external) / kafka:9092 (internal)
echo   - Zookeeper: %MASTER_IP%:2181
echo.
echo WORKER NODE SETUP:
echo   1. Copy this IP address: %MASTER_IP%
echo   2. Run setup-worker.bat on each worker machine
echo   3. Enter the IP when prompted: %MASTER_IP%
echo.
echo KAFKA NETWORKING:
echo   - Internal services use: kafka:9092
echo   - External clients use:  %MASTER_IP%:19092
echo.
echo Logs are available in the 'logs' directory
echo Docker service logs: docker-compose logs -f
echo ================================================================

timeout /t 5 /nobreak >nul

REM Test if the master started successfully
curl -s http://localhost:8080/actuator/health >nul 2>&1
if not errorlevel 1 (
    echo.
    echo SUCCESS: Master node is running and healthy!
    echo You can now access the dashboard at: http://%MASTER_IP%:8080
) else (
    echo.
    echo NOTE: Master node is starting up. Please wait a moment and check http://%MASTER_IP%:8080
)

echo.
pause
