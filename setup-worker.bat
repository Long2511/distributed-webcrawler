@echo off
REM Distributed Web Crawler - Worker Node Setup Script (Using Existing Services)
REM This script sets up a worker node that connects to an existing master with services

echo Setting up Distributed Web Crawler - Worker Node (Existing Services)
echo =========================================================================
echo.
echo You need the MASTER NODE IP ADDRESS to continue.
echo    Run the master setup script first and note the displayed IP.
echo.
set /p MASTER_IP=Enter the Master Node IP address: 
echo.
echo Connecting to master node at: %MASTER_IP%
echo    - Redis: %MASTER_IP%:6379
echo    - MongoDB: %MASTER_IP%:27017
echo    - Kafka: %MASTER_IP%:9092
echo.

REM Get the current machine's IP address
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "ipv4"') do (
    for /f "tokens=1" %%b in ("%%a") do (
        set WORKER_IP=%%b
        goto :found_ip
    )
)
:found_ip
set WORKER_IP=%WORKER_IP: =%
echo This worker's IP address: %WORKER_IP%

REM Create necessary directories
echo Creating directories...
if not exist logs mkdir logs
if not exist config mkdir config

REM Create worker configuration from template
echo Creating worker configuration...
if not exist config\worker-node.properties (
    echo Worker node template not found at config\worker-node.properties
    echo Please ensure the configuration template exists.
    pause
    exit /b 1
)

copy config\worker-node.properties config\worker-node-local.properties >nul

REM Update configuration with actual IPs
echo Configuring worker to connect to master at %MASTER_IP%...
echo    Setting up Redis connection to %MASTER_IP%:6379
echo    Setting up MongoDB connection to %MASTER_IP%:27017
echo    Setting up Kafka connection to %MASTER_IP%:9092
echo    Setting worker advertised host to %WORKER_IP%

powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'spring.redis.host=.*', 'spring.redis.host=%MASTER_IP%' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'spring.kafka.bootstrap-servers=.*', 'spring.kafka.bootstrap-servers=%MASTER_IP%:9092' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'spring.data.mongodb.uri=.*', 'spring.data.mongodb.uri=mongodb://%MASTER_IP%:27017/webcrawler' | Set-Content config\worker-node-local.properties"
powershell -Command "(Get-Content config\worker-node-local.properties) -replace 'webcrawler.instance.advertised-host=.*', 'webcrawler.instance.advertised-host=%WORKER_IP%' | Set-Content config\worker-node-local.properties"

REM Test connection to master services
echo.
echo Testing connectivity to master services at %MASTER_IP%...
echo ================================================================

echo [1/3] Testing Redis connection (%MASTER_IP%:6379)...
powershell -Command "Test-NetConnection -ComputerName %MASTER_IP% -Port 6379 -InformationLevel Quiet" >nul 2>&1
if %errorlevel% equ 0 (
    echo Redis port is accessible
    redis-cli -h %MASTER_IP% ping >nul 2>&1
    if errorlevel 1 (
        echo WARNING: Redis port open but Redis service not responding
    ) else (
        echo Redis connection successful
    )
) else (
    echo ERROR: Cannot connect to Redis port %MASTER_IP%:6379
    echo Please check: 1) Redis is running, 2) Firewall allows port 6379
    set /p CONTINUE=Continue anyway? (y/n): 
    if /i not "%CONTINUE%"=="y" exit /b 1
)

echo [2/3] Testing MongoDB connection (%MASTER_IP%:27017)...
powershell -Command "Test-NetConnection -ComputerName %MASTER_IP% -Port 27017 -InformationLevel Quiet" >nul 2>&1
if %errorlevel% equ 0 (
    echo MongoDB port is accessible
    REM Try to connect with timeout
    powershell -Command "try { $conn = [System.Net.Sockets.TcpClient]::new(); $task = $conn.ConnectAsync('%MASTER_IP%', 27017); if ($task.Wait(5000)) { $conn.Close(); exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>&1
    if errorlevel 1 (
        echo WARNING: MongoDB port open but connection test failed
        echo This might indicate MongoDB authentication or configuration issues
    ) else (
        echo MongoDB connection successful
    )
) else (
    echo ERROR: Cannot connect to MongoDB port %MASTER_IP%:27017
    echo Please check: 1) MongoDB is running, 2) Firewall allows port 27017
    set /p CONTINUE=Continue anyway? (y/n): 
    if /i not "%CONTINUE%"=="y" exit /b 1
)

echo [3/3] Testing Kafka connection (%MASTER_IP%:9092)...
powershell -Command "Test-NetConnection -ComputerName %MASTER_IP% -Port 9092 -InformationLevel Quiet" >nul 2>&1
if %errorlevel% equ 0 (
    echo Kafka port is accessible
    REM Basic Kafka test would need Kafka tools, so just test port connectivity
    echo Kafka connection test completed
) else (
    echo ERROR: Cannot connect to Kafka port %MASTER_IP%:9092
    echo Please check: 1) Kafka is running, 2) Firewall allows port 9092, 3) Kafka advertised.listeners configured
    set /p CONTINUE=Continue anyway? (y/n): 
    if /i not "%CONTINUE%"=="y" exit /b 1
)

echo.
echo Connectivity tests completed. Starting application...
echo.


REM Build the web crawler application if not already built
if not exist target\webcrawler-1.0-SNAPSHOT.jar (
    echo Building web crawler application...
    call mvn clean package -DskipTests
    if errorlevel 1 (
        echo Build failed. Please check the build errors.
        pause
        exit /b 1
    )
) else (
    echo Application already built
)

REM Start worker node
echo Starting worker node...
echo Worker will connect to master at %MASTER_IP% and listen on %WORKER_IP%:8081
start "WebCrawler Worker" java -jar -Dspring.config.location=config/worker-node-local.properties target/webcrawler-1.0-SNAPSHOT.jar

REM Wait a moment for the application to start
echo Waiting for worker to start...
timeout /t 10 /nobreak >nul

REM Test if the worker started successfully
curl -s http://localhost:8081/actuator/health >nul 2>&1
if errorlevel 1 (
    echo Worker may not have started successfully. Check the logs for errors.
) else (
    echo Worker started successfully!
)

echo.
echo Worker node setup complete!
echo.
echo This worker node:
echo   - Worker IP: %WORKER_IP%:8081
echo   - Health Check: http://%WORKER_IP%:8081/actuator/health
echo   - Connecting to Master: %MASTER_IP%
echo   - Log file: logs/webcrawler-worker.log
echo.
echo Check the master node UI to see this worker join the cluster
echo Master UI: http://%MASTER_IP%:8080
echo.
echo Press any key to view the worker log file...
pause >nul
start notepad logs/webcrawler-worker.log
