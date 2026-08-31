@echo off
setlocal
chcp 65001 >nul
set "AUTOLIVE_PROJECT=%~dp0."
call "%~dp0..\umamo\gradlew.bat" -p "%AUTOLIVE_PROJECT%" run %*
endlocal
