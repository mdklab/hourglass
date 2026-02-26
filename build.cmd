@echo off
setlocal
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out HourglassApp.java
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
echo Build OK.
endlocal
