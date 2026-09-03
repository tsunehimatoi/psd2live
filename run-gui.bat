@echo off
setlocal
chcp 65001 >nul
set "PSD2LIVE_PROJECT=%~dp0."
call "%~dp0..\umamo\gradlew.bat" -p "%PSD2LIVE_PROJECT%" run %*
endlocal
