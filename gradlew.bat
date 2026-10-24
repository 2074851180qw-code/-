@echo off
cd /d "%~dp0"
if not exist gradle\wrapper\gradle-wrapper.jar (
  where gradle >nul 2>nul
  if errorlevel 1 (
    echo 缺少 gradle\wrapper\gradle-wrapper.jar。请用 Android Studio 打开本目录，或先安装 gradle 再运行。
    exit /b 1
  )
  gradle wrapper --gradle-version 8.9 || exit /b 1
  call gradlew.bat %*
  exit /b %errorlevel%
)
