@echo off
chcp 65001 >nul
REM ============================================================
REM  启动后端
REM  JDK：你系统默认的 java 指向了已损坏的 java8 目录，这里显式指定 17
REM  Maven：优先用项目自带的 Maven Wrapper（mvnw.cmd），无需配置环境变量
REM ============================================================
set "JAVA_HOME=D:\7.Environment\jdk-17.0.12"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [错误] 找不到 JDK 17: %JAVA_HOME%
  echo 请修改本脚本第 8 行的 JAVA_HOME 为你的 JDK 17 路径
  pause & exit /b 1
)

cd /d "%~dp0BackEnd"

echo [1/2] Java 版本:
java -version
echo.

if exist "mvnw.cmd" (
  echo [2/2] 启动 Spring Boot ^(Maven Wrapper^) ...
  call mvnw.cmd spring-boot:run
) else (
  echo [2/2] 启动 Spring Boot ^(项目自带 Maven^) ...
  call "%~dp0tools\apache-maven-3.9.16\bin\mvn.cmd" spring-boot:run
)
pause
