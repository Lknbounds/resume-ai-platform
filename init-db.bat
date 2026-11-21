@echo off
chcp 65001 >nul
REM ============================================================
REM  初始化数据库 resume_ai（会先 DROP，已有数据请勿乱跑）
REM  只有在你想要"从零重建表结构"时才需要执行。
REM  正常情况下 Flyway 会在后端首次启动时自动建表。
REM ============================================================
set "MYSQL=D:\7.Environment\Mysql\MySQL Server 8.0\bin\mysql.exe"
set "SQL=%~dp0BackEnd\src\main\resources\db\migration\V1__init.sql"

if not exist "%MYSQL%" (
  echo [错误] 找不到 mysql.exe: %MYSQL%
  pause & exit /b 1
)

echo 即将重建数据库 resume_ai（当前数据会被清空）
choice /c YN /m "确认继续?"
if errorlevel 2 exit /b 0

"%MYSQL%" -uroot -p123456 --default-character-set=utf8mb4 -e "DROP DATABASE IF EXISTS resume_ai; CREATE DATABASE resume_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
"%MYSQL%" -uroot -p123456 --default-character-set=utf8mb4 resume_ai < "%SQL%"
echo.
echo 完成。表数量:
"%MYSQL%" -uroot -p123456 --default-character-set=utf8mb4 -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='resume_ai';"
pause
