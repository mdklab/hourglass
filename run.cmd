@echo off
setlocal
cd /d "%~dp0"
if not exist out\HourglassApp.class (
  echo Build first: run build.cmd
  exit /b 1
)
start "" javaw -cp out HourglassApp
endlocal
