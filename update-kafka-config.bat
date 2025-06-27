@echo off
REM Update Kafka Configuration for Distributed Setup
REM This script updates Kafka's server.properties to use the correct advertised listeners

echo =======================================================
echo Updating Kafka Configuration for Distributed Setup
echo =======================================================
echo.
echo 📋 Enter the master node IP address that workers should connect to.
echo.

set /p MASTER_IP=🔗 Enter Master Node IP address: 
echo.
echo Master node IP: %MASTER_IP%
echo.

REM Check if Kafka is installed
if not exist "%KAFKA_HOME%\config\server.properties" if not exist "kafka\config\server.properties" (
    echo ERROR: Kafka server.properties not found!
    echo Please ensure Kafka is installed and KAFKA_HOME is set, or run this script from Kafka directory
    pause
    exit /b 1
)

REM Determine Kafka config path
if exist "%KAFKA_HOME%\config\server.properties" (
    set KAFKA_CONFIG=%KAFKA_HOME%\config\server.properties
    set KAFKA_CONFIG_BACKUP=%KAFKA_HOME%\config\server.properties.backup
) else (
    set KAFKA_CONFIG=kafka\config\server.properties
    set KAFKA_CONFIG_BACKUP=kafka\config\server.properties.backup
)

echo Kafka config file: %KAFKA_CONFIG%
echo.

REM Create backup
echo Creating backup of server.properties...
copy "%KAFKA_CONFIG%" "%KAFKA_CONFIG_BACKUP%" >nul
if %errorlevel% neq 0 (
    echo ERROR: Failed to create backup
    pause
    exit /b 1
)
echo Backup created: %KAFKA_CONFIG_BACKUP%
echo.

REM Update configuration
echo Updating Kafka configuration...

REM Create temporary file with updated configuration
(
    for /f "usebackq delims=" %%a in ("%KAFKA_CONFIG_BACKUP%") do (
        set "line=%%a"
        setlocal enabledelayedexpansion
        
        REM Update listeners
        if "!line:~0,10!"=="listeners=" (
            echo listeners=PLAINTEXT://0.0.0.0:9092
        ) else if "!line:~0,21!"=="advertised.listeners=" (
            echo advertised.listeners=PLAINTEXT://%MASTER_IP%:9092
        ) else if "!line:~0,1!"=="#" if "!line:~1,21!"=="advertised.listeners=" (
            echo advertised.listeners=PLAINTEXT://%MASTER_IP%:9092
        ) else (
            echo !line!
        )
        endlocal
    )
) > "%KAFKA_CONFIG%"

echo.
echo ✓ Kafka configuration updated successfully!
echo.
echo Key changes made:
echo - listeners=PLAINTEXT://0.0.0.0:9092
echo - advertised.listeners=PLAINTEXT://%MASTER_IP%:9092
echo.
echo Next steps:
echo 1. Restart Kafka service if it's already running
echo 2. Copy the updated config files to all machines:
echo    - config\master-node.properties to master machine
echo    - config\worker-node.properties to worker machines
echo 3. Update worker-node.properties on each worker with correct IP addresses
echo.
pause
