@echo off
setlocal
chcp 65001 >nul
call "%~dp0gradlew.bat" -p "%~dp0." run %*
endlocal
