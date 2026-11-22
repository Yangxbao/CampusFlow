@echo off
REM ============================================================
REM  run_with_gclog.bat - 带 GC 日志运行任意 main 类的脚本模板
REM
REM  用法:
REM    run_with_gclog.bat <完整类名> [cp=类路径] [xmx=堆上限] [args=主类参数]
REM  示例:
REM    run_with_gclog.bat com.campusflow.lab.jvm.gc.GcLogLab
REM    run_with_gclog.bat com.campusflow.lab.jvm.gc.GcLogLab cp=target\classes xmx=256m
REM    run_with_gclog.bat com.campusflow.lab.jvm.gc.GcCompareLab cp=target\classes xmx=256m args=zgc
REM
REM  输出:
REM    gc_YYYYMMDD_HHMMSS.log（GC 日志，-Xlog:gc* 全量明细）
REM    控制台同时打印程序 stdout
REM
REM  JDK17 统一日志说明（-Xlog 语法）:
REM    -Xlog:gc*:file=gc.log:time,uptime,level,tags
REM           ^tag集    ^输出      ^装饰字段
REM    tag 集可组合: gc+heap+exit; 级别: trace/debug/info/warning/error;
REM    轮转: filecount=5,filesize=20m（避免 gc.log 无限膨胀）;
REM    JDK8 时代的 -XX:+PrintGCDetails -XX:+PrintGCDateStamps 在 JDK9+ 已移除。
REM ============================================================
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo [ERROR] 用法: %~nx0 ^<完整类名^> [cp=类路径] [xmx=堆上限] [args=主类参数]
    exit /b 1
)

set "MAIN_CLASS=%~1"
set "CP=target\classes"
set "XMX=256m"
set "APP_ARGS="
for %%a in (%*) do (
    echo %%a | findstr /b "cp=" >nul && for /f "tokens=1* delims==" %%k in ("%%a") do set "CP=%%l"
    echo %%a | findstr /b "xmx=" >nul && for /f "tokens=1* delims==" %%k in ("%%a") do set "XMX=%%l"
    echo %%a | findstr /b "args=" >nul && for /f "tokens=1* delims==" %%k in ("%%a") do set "APP_ARGS=%%l"
)

rem ---- 定位 java ----
set "JAVA_CMD="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if "%JAVA_CMD%"=="" set "JAVA_CMD=java"

for /f "tokens=1-3 delims=/:. " %%a in ("%time: =0%") do set "TS=%%a%%b%%c"
set "GCLOG=gc_%TS%.log"

echo [INFO] 类: %MAIN_CLASS%  cp: %CP%  xmx: %XMX%
echo [INFO] GC 日志: %GCLOG%  主类参数: %APP_ARGS%

"%JAVA_CMD%" ^
  -Xms%XMX% -Xmx%XMX% ^
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 ^
  -Xlog:gc*,gc+heap=debug,gc+humongous=debug:file=%GCLOG%:time,uptime,level,tags ^
  -Dfile.encoding=UTF-8 ^
  -cp "%CP%" ^
  %MAIN_CLASS% %APP_ARGS%

if errorlevel 1 (
    echo [ERROR] 程序异常退出，exitcode=%errorlevel%
) else (
    echo.
    echo [INFO] 运行完成。查看 GC 日志:
    echo         type %GCLOG%
    echo [INFO] 统计暂停: findstr /C:"Pause" %GCLOG%
)
exit /b 0
