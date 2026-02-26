@echo off
setlocal
cd /d "%~dp0"

call .\build.cmd
if errorlevel 1 exit /b 1

set "JDK_BIN="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" (
  set "JDK_BIN=%JAVA_HOME%\bin"
)

if not defined JDK_BIN (
  for %%D in ("%USERPROFILE%\.jdks\corretto-21.0.2\bin" "%USERPROFILE%\.jdks\jbr-17.0.12\bin" "%USERPROFILE%\.jdks\openjdk-19.0.1\bin") do (
    if exist "%%~fD\jpackage.exe" (
      set "JDK_BIN=%%~fD"
      goto :jdk_found
    )
  )
)

if not defined JDK_BIN (
  for /f "delims=" %%I in ('where jpackage 2^>nul') do (
    set "JPACKAGE_EXE=%%~fI"
    goto :path_found
  )
  echo jpackage not found. Install JDK 14+ with jpackage and set JAVA_HOME or PATH.
  exit /b 1
)

:jdk_found
set "JPACKAGE_EXE=%JDK_BIN%\jpackage.exe"
set "JAR_EXE=%JDK_BIN%\jar.exe"
goto :tools_ready

:path_found
for %%I in ("%JPACKAGE_EXE%") do set "JDK_BIN=%%~dpI"
set "JAR_EXE=%JDK_BIN%jar.exe"

:tools_ready
if not exist "%JAR_EXE%" (
  echo jar.exe not found near jpackage: "%JAR_EXE%"
  exit /b 1
)

set "PACKAGE_INPUT=package\input"
set "PACKAGE_OUTPUT=package\output"
set "APP_NAME=Hourglass"

if not exist "%PACKAGE_INPUT%" mkdir "%PACKAGE_INPUT%"
if not exist "%PACKAGE_OUTPUT%" mkdir "%PACKAGE_OUTPUT%"

del /f /q "%PACKAGE_INPUT%\hourglass.jar" >nul 2>nul
rmdir /s /q "%PACKAGE_OUTPUT%\%APP_NAME%" >nul 2>nul

"%JAR_EXE%" --create --file "%PACKAGE_INPUT%\hourglass.jar" -C out .
if errorlevel 1 (
  echo Failed to create JAR.
  exit /b 1
)

"%JPACKAGE_EXE%" --type app-image --input "%PACKAGE_INPUT%" --dest "%PACKAGE_OUTPUT%" --name "%APP_NAME%" --main-jar hourglass.jar --main-class HourglassApp --app-version 1.0.0 --vendor "Hourglass"
if errorlevel 1 (
  echo jpackage failed.
  exit /b 1
)

echo EXE ready: "%cd%\%PACKAGE_OUTPUT%\%APP_NAME%\%APP_NAME%.exe"
endlocal
