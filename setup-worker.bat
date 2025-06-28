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


REM Get master IP from user
echo [1/7] Getting master node information...
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

REM Get the current machine's IP address
echo [2/7] Detecting worker node IP address...
for /f "skip=1 tokens=1" %%a in ('wmic computersystem get name') do if not defined COMPUTER_NAME set COMPUTER_NAME=%%a
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "ipv4" ^| findstr /v "127.0.0.1" ^| findstr /v "169.254"') do (
    for /f "tokens=1" %%b in ("%%a") do (
        set WORKER_IP=%%b
        goto :found_worker_ip
    )
)
:found_worker_ip
set WORKER_IP=%WORKER_IP: =%

if "%WORKER_IP%"=="" (
    echo ERROR: Could not detect worker IP address automatically.
    set /p WORKER_IP=Please enter this machine's IP address manually: 
)

echo This worker's IP address: %WORKER_IP%
echo Worker will be accessible at: http://%WORKER_IP%:8081

REM Create necessary directories
echo [3/7] Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config
echo Directories created.


REM Create worker configuration
echo [4/7] Creating worker configuration...
if exist config\worker-node.properties (
    copy config\worker-node.properties config\worker-node-local.properties >nul
    echo Using existing worker-node.properties as template.
) else (
    echo Creating new worker configuration...
    echo # Worker Node Configuration > config\worker-node-local.properties
    echo server.port=8081 >> config\worker-node-local.properties
    echo webcrawler.instance.type=worker >> config\worker-node-local.properties
    echo webcrawler.instance.advertised-host=%WORKER_IP% >> config\worker-node-local.properties
    echo. >> config\worker-node-local.properties
    echo # Redis Configuration >> config\worker-node-local.properties
    echo spring.redis.host=%MASTER_IP% >> config\worker-node-local.properties
    echo spring.redis.port=6379 >> config\worker-node-local.properties
    echo. >> config\worker-node-local.properties
    echo # Kafka Configuration (using external listener) >> config\worker-node-local.properties
    echo spring.kafka.bootstrap-servers=%MASTER_IP%:19092 >> config\worker-node-local.properties
    echo spring.kafka.consumer.group-id=webcrawler-workers >> config\worker-node-local.properties
    echo spring.kafka.consumer.auto-offset-reset=earliest >> config\worker-node-local.properties
    echo. >> config\worker-node-local.properties
    echo # MongoDB Configuration >> config\worker-node-local.properties
    echo spring.data.mongodb.uri=mongodb://%MASTER_IP%:27017/webcrawler >> config\worker-node-local.properties
    echo. >> config\worker-node-local.properties
    echo # Logging Configuration >> config\worker-node-local.properties
    echo logging.file.name=logs/webcrawler-worker.log >> config\worker-node-local.properties
    echo logging.level.com.ouroboros.webcrawler=INFO >> config\worker-node-local.properties
)

REM Update configuration with actual IPs
echo Configuring worker to connect to master at %MASTER_IP%...
echo    Setting up Redis connection to %MASTER_IP%:6379
echo    Setting up MongoDB connection to %MASTER_IP%:27017
echo    Setting up Kafka connection to %MASTER_IP%:19092 (external)
echo    Setting worker advertised host to %WORKER_IP%

powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'spring.redis.host=.*', 'spring.redis.host=%MASTER_IP%' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'spring.kafka.bootstrap-servers=.*', 'spring.kafka.bootstrap-servers=%MASTER_IP%:19092' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'spring.data.mongodb.uri=.*', 'spring.data.mongodb.uri=mongodb://%MASTER_IP%:27017/webcrawler' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'webcrawler.instance.advertised-host=.*', 'webcrawler.instance.advertised-host=%WORKER_IP%' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'server.port=.*', 'server.port=8081' | Set-Content config\worker-node-local.properties"

echo Worker configuration completed.

REM Build the web crawler application if needed
echo [5/7] Building web crawler application...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo ERROR: Build failed. Please check the build errors above.
    pause
    exit /b 1
)

REM Start worker node
echo [6/7] Starting worker node...
start "WebCrawler Worker - %WORKER_IP%" java -Dspring.config.location=config/worker-node-local.properties -jar target/webcrawler-1.0-SNAPSHOT.jar

REM Wait for the application to start
echo Waiting for worker to initialize...
timeout /t 15 /nobreak >nul

REM Test if the worker started successfully
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
