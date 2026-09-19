@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Build live2d_renderer.dll with static MSVC CRT (/MT), matching the validated
rem dependency profile: OPENGL32 / KERNEL32 / USER32 / GDI32 only — no VCRUNTIME.
rem
rem Usage:
rem   set CUBISM_SDK_ROOT=C:\path\to\CubismSdkForNative-5-r.5
rem   native\build_live2d_renderer.bat
rem   native\build_live2d_renderer.bat -Deploy
rem   native\build_live2d_renderer.bat -Clean -Deploy
rem   native\build_live2d_renderer.bat -DCUBISM_SDK_ROOT=C:\path\to\CubismSdkForNative-5-r.5

set "SCRIPT_DIR=%~dp0"
set "REPO_ROOT=%SCRIPT_DIR%.."
set "SRC_DIR=%SCRIPT_DIR%live2d_renderer"
set "BUILD_DIR=%SRC_DIR%\build"
set "DEPLOY=0"
set "CLEAN=0"

for %%A in (%*) do (
  if /I "%%~A"=="-Deploy" set "DEPLOY=1"
  if /I "%%~A"=="/Deploy" set "DEPLOY=1"
  if /I "%%~A"=="-Clean" set "CLEAN=1"
  if /I "%%~A"=="/Clean" set "CLEAN=1"
  set "ARG=%%~A"
  if /I "!ARG:~0,18!"=="-DCUBISM_SDK_ROOT=" (
    set "CUBISM_SDK_ROOT=!ARG:~18!"
  )
)

if not defined CUBISM_SDK_ROOT (
  echo [ERROR] CUBISM_SDK_ROOT is not set.
  echo Download Cubism 5 SDK for Native, extract it, then either:
  echo   set CUBISM_SDK_ROOT=C:\path\to\CubismSdkForNative-5-r.5
  echo or pass:
  echo   %~nx0 -DCUBISM_SDK_ROOT=C:\path\to\CubismSdkForNative-5-r.5
  exit /b 1
)

if not exist "%CUBISM_SDK_ROOT%\Core\include\Live2DCubismCore.h" (
  echo [ERROR] CUBISM_SDK_ROOT does not look like CubismSdkForNative-5-r.5:
  echo   %CUBISM_SDK_ROOT%
  exit /b 1
)

if not exist "%CUBISM_SDK_ROOT%\Core\lib\windows\x86_64\143\Live2DCubismCore_MT.lib" (
  echo [ERROR] Missing Live2DCubismCore_MT.lib ^(required for /MT static CRT^).
  echo Expected under:
  echo   %CUBISM_SDK_ROOT%\Core\lib\windows\x86_64\143\
  exit /b 1
)

echo ===================================================
echo  Building live2d_renderer.dll  [/MT static CRT]
echo  SDK: %CUBISM_SDK_ROOT%
echo ===================================================

where cmake >nul 2>nul
if errorlevel 1 (
  rem Fall back to common install locations when cmake is not on PATH.
  if exist "%LOCALAPPDATA%\Android\Sdk\cmake\3.31.4\bin\cmake.exe" (
    set "PATH=%LOCALAPPDATA%\Android\Sdk\cmake\3.31.4\bin;%PATH%"
  ) else if exist "%ProgramFiles%\CMake\bin\cmake.exe" (
    set "PATH=%ProgramFiles%\CMake\bin;%PATH%"
  ) else if exist "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" (
    set "PATH=%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin;%PATH%"
  )
)
where cmake >nul 2>nul
if errorlevel 1 (
  echo [ERROR] cmake not found on PATH. Install CMake 3.16+ and retry.
  exit /b 1
)

rem Drop a stale /MD cache so runtime-library changes actually take effect.
if exist "%BUILD_DIR%\CMakeCache.txt" (
  findstr /C:"CMAKE_MSVC_RUNTIME_LIBRARY:STRING=MultiThreadedDLL" "%BUILD_DIR%\CMakeCache.txt" >nul 2>nul
  if not errorlevel 1 (
    echo [INFO] Previous build used /MD — forcing clean reconfigure for /MT.
    set "CLEAN=1"
  )
  findstr /C:"Live2DCubismCore_MD.lib" "%BUILD_DIR%\CMakeCache.txt" >nul 2>nul
  if not errorlevel 1 (
    echo [INFO] Previous build linked Core_MD.lib — forcing clean reconfigure for Core_MT.lib.
    set "CLEAN=1"
  )
)

if "%CLEAN%"=="1" (
  echo [0/3] Cleaning "%BUILD_DIR%" ...
  if exist "%BUILD_DIR%" rmdir /S /Q "%BUILD_DIR%"
)

if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

echo [1/3] Configuring CMake [/MT + Core_MT.lib]...
cmake -G "Visual Studio 17 2022" -A x64 ^
  -DCUBISM_SDK_ROOT="%CUBISM_SDK_ROOT%" ^
  -DCMAKE_MSVC_RUNTIME_LIBRARY="MultiThreaded$<$<CONFIG:Debug>:Debug>" ^
  -B "%BUILD_DIR%" ^
  -S "%SRC_DIR%"
if errorlevel 1 (
  echo [ERROR] CMake configuration failed.
  exit /b 1
)

echo [2/3] Building Release...
cmake --build "%BUILD_DIR%" --config Release --target live2d_renderer
if errorlevel 1 (
  echo [ERROR] Build failed.
  exit /b 1
)

set "DLL_OUT=%BUILD_DIR%\bin\Release\live2d_renderer.dll"
set "SHADER_OUT=%BUILD_DIR%\bin\Release\FrameworkShaders"
if not exist "%DLL_OUT%" (
  echo [ERROR] Expected output missing: %DLL_OUT%
  exit /b 1
)

echo.
echo  Build Successful!
echo  DLL:     %DLL_OUT%
echo  Shaders: %SHADER_OUT%
echo  Expected dependents: OPENGL32 KERNEL32 USER32 GDI32  ^(no VCRUNTIME/MSVCP^)

if "%DEPLOY%"=="1" (
  echo [3/3] Deploying to src\main\resources\cubism\windows-x86_64\ ...
  set "DEST=%REPO_ROOT%\src\main\resources\cubism\windows-x86_64"
  if not exist "%DEST%" mkdir "%DEST%"
  copy /Y "%DLL_OUT%" "%DEST%\live2d_renderer.dll" >nul
  if exist "%DEST%\FrameworkShaders" rmdir /S /Q "%DEST%\FrameworkShaders"
  xcopy /E /I /Y "%SHADER_OUT%" "%DEST%\FrameworkShaders" >nul
  echo  Deployed to %DEST%
  echo  ^(path is gitignored; will not be committed^)
) else (
  echo [3/3] Skip deploy. Pass -Deploy to copy into resources\, or copy manually.
)

echo ===================================================
exit /b 0
