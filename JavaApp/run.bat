@echo off
title Intelligent Assistant
color 0A
echo ==================================================
echo Starting Intelligent Assistant
echo Using the default system microphone and speakers
echo ==================================================

cd /d "%~dp0"

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

call "%MVN_CMD%" compile exec:java "-Dexec.mainClass=com.assistant.IntelligentAssistant"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Application exited with error code: %ERRORLEVEL%
    pause
)
