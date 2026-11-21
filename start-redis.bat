@echo off
chcp 65001 >nul
REM ============================================================
REM  启动本地 Redis（解压版 5.0.14.1），端口 6379
REM  关闭本窗口即停止 Redis
REM ============================================================
set "REDIS_DIR=%~dp0tools\Redis-x64-5.0.14.1"
set "REDIS=%REDIS_DIR%\redis-server.exe"

if not exist "%REDIS%" (
  echo [错误] 未找到 %REDIS%
  echo 请确认 tools\Redis-x64-5.0.14.1\ 目录存在
  pause & exit /b 1
)

echo Redis 启动中... 端口 6379，关闭此窗口即停止
echo 验证：tools\Redis-x64-5.0.14.1\redis-cli.exe ping  ^(应返回 PONG^)
echo.
cd /d "%REDIS_DIR%"
"%REDIS%" redis.windows.conf
pause
