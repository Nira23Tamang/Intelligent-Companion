@echo off
title Intelligent Assistant Launcher
color 0B
echo ======================================================================
echo           INTELLIGENT ASSISTANT -- AI DESKTOP COMPANION
echo ======================================================================
echo.
echo  [*] System Initializing...
echo  [*] Authentication : Email/password or optional face login
echo  [*] Input Microphone : Default system input
echo  [*] Output Audio     : Default system output
echo  [*] Loading Piper Neural TTS ^& Vosk Speech Recognizer...
echo  [*] Launching Web UI at http://localhost:8080 ...
echo.
echo ======================================================================
echo.

cd /d "%~dp0"

cd /d "%~dp0JavaApp"

:: Auto-detect JDK
if not exist "%JAVA_HOME%\bin\java.exe" (
    if exist "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot" (
        set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
    )
)

set OPENCV_OPENCL_DEVICE=disabled

:: Auto-detect Maven
if exist ".\apache-maven-3.9.6\bin\mvn.cmd" (
    set "MVN_CMD=.\apache-maven-3.9.6\bin\mvn.cmd"
) else if exist "..\Old\JavaApp\apache-maven-3.9.6\bin\mvn.cmd" (
    set "MVN_CMD=..\Old\JavaApp\apache-maven-3.9.6\bin\mvn.cmd"
) else if exist "%USERPROFILE%\.maven\maven-3.10.0-rc-1\bin\mvn.cmd" (
    set "MVN_CMD=%USERPROFILE%\.maven\maven-3.10.0-rc-1\bin\mvn.cmd"
) else (
    set "MVN_CMD=mvn"
)

call "%MVN_CMD%" exec:java -D"exec.mainClass"="com.assistant.IntelligentAssistant"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ======================================================================
    echo  [ERROR] Application exited with error code: %ERRORLEVEL%
    echo  Please check if another instance or port is currently running.
    echo ======================================================================
    pause
)