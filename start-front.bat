@echo off
chcp 65001 >nul
REM 启动前端开发服务器（http://localhost:5173）
cd /d "%~dp0Front"
if not exist "node_modules" (
  echo 首次运行，安装依赖...
  call npm install
)
call npm run dev
pause
