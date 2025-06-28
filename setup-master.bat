@echo off
REM Distributed Web Crawler - Master Node Setup Script (Windows)
REM This script sets up the master node with all central services including proper Kafka networking

echo =================================================================
echo   DISTRIBUTED WEB CRAWLER - MASTER NODE SETUP
echo =================================================================
echo.

REM Get the current machine's IP address
echo [1/6] Detecting master node IP address...
for /f "skip=1 tokens=1" %%a in ('wmic computersystem get name') do if not defined COMPUTER_NAME set COMPUTER_NAME=%%a
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "ipv4" ^| findstr /v "127.0.0.1" ^| findstr /v "169.254"') do (
    for /f "tokens=1" %%b in ("%%a") do (
        set MASTER_IP=%%b
        goto :found_ip
    )
)
:found_ip
set MASTER_IP=%MASTER_IP: =%

if "%MASTER_IP%"=="" (
    echo ERROR: Could not detect IP address automatically.
    set /p MASTER_IP=Please enter your machine's IP address manually: 
)

echo.
echo ================================
echo MASTER NODE IP ADDRESS: %MASTER_IP%
echo ================================
echo.
echo IMPORTANT: Workers must use this IP address to connect!
echo Share this IP with all worker machines: %MASTER_IP%
echo.

REM Create necessary directories
echo [2/6] Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config
if not exist data mkdir data
echo Directories created.

REM Set up environment for Kafka
echo [3/6] Setting up Kafka networking configuration...
echo HOST_IP=%MASTER_IP% > .env
set HOST_IP=%MASTER_IP%
echo Kafka external access configured for IP: %MASTER_IP%

REM Update docker-compose.yml with proper Kafka configuration
echo Updating Kafka configuration in docker-compose.yml...
powershell -Command "$content = Get-Content 'docker-compose.yml' -Raw; $content = $content -replace 'EXTERNAL://\$\{HOST_IP:-localhost\}:19092', 'EXTERNAL://%MASTER_IP%:19092'; Set-Content 'docker-compose.yml' -Value $content"

REM Update configuration files with actual IP
echo Updating configuration files with detected IP: %MASTER_IP%
if exist config\master-node.properties (
    powershell -Command "(Get-Content config\master-node.properties) -replace '192.168.1.100', '%MASTER_IP%' -replace 'localhost', '%MASTER_IP%' -replace ':9092', ':19092' -replace '172.16.128.246', '%MASTER_IP%' | Set-Content config\master-node.properties"
    echo Master node properties updated.
) else (
    echo WARNING: config\master-node.properties not found. Creating basic configuration...
    echo # Master Node Configuration > config\master-node.properties
    echo server.port=8080 >> config\master-node.properties
    echo webcrawler.instance.type=master >> config\master-node.properties
    echo webcrawler.instance.advertised-host=%MASTER_IP% >> config\master-node.properties
    echo spring.redis.host=%MASTER_IP% >> config\master-node.properties
    echo spring.redis.port=6379 >> config\master-node.properties
    echo spring.kafka.bootstrap-servers=%MASTER_IP%:19092 >> config\master-node.properties
    echo spring.data.mongodb.uri=mongodb://%MASTER_IP%:27017/webcrawler >> config\master-node.properties
)

REM Stop any existing services
echo [4/6] Stopping existing services...
docker-compose down >nul 2>&1

REM Clean up Kafka and Zookeeper data to prevent cluster ID conflicts
echo Cleaning up previous Kafka/Zookeeper data to prevent conflicts...
docker volume rm distributed-webcrawler_kafka_data distributed-webcrawler_zookeeper_data >nul 2>&1
echo Previous data cleaned.

REM Start central services
echo Starting central services (MongoDB, Redis, Kafka, Zookeeper, Web UIs)...
docker-compose up -d

REM Wait for services to be ready
echo [5/6] Waiting for services to initialize...
echo This may take up to 30 seconds...
timeout /t 15 /nobreak >nul

REM Check service health
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

REM Build the web crawler application
echo [6/6] Building web crawler application...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo ERROR: Build failed. Please check the build errors above.
    pause
    exit /b 1
)

REM Start master node application
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

REM Wait a moment for the application to start
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
