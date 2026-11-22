@echo off
REM ============================================================
REM  jcmd_diagnose.bat - 用 jcmd 一次性采集 JVM 多维度诊断信息
REM
REM  用法:
REM    jcmd_diagnose.bat <pid> [输出目录] [--with-heap-dump]
REM  示例:
REM    jcmd_diagnose.bat 12345 .\diagnostics
REM    jcmd_diagnose.bat 12345 .\diagnostics --with-heap-dump
REM
REM  采集内容:
REM    VM.uptime           JVM 运行时长
REM    VM.flags            当前生效的全部 VM 标志（调优验证必备）
REM    VM.command_line     完整启动命令行
REM    Thread.print        线程转储（等价 jstack，含死锁检测输出）
REM    GC.class_histogram  类直方图（实例数/字节数 Top 类，快速发现类爆炸与集合膨胀）
REM    GC.heap_info        各代/Region 内存概况
REM    GC.heap_dump        (可选)堆转储 .hprof
REM
REM  说明: jcmd 是 JDK7+ 推荐的一站式诊断入口，与目标 JVM 同版本时优先用它，
REM        比 jstack/jmap 权限问题更少；执行类直方图/heap_dump 会有一次 Full GC 开销(:live 语义)。
REM ============================================================
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo [ERROR] 用法: %~nx0 ^<pid^> [输出目录] [--with-heap-dump]
    exit /b 1
)

set "PID=%~1"
set "OUTDIR=%~2"
if "%OUTDIR%"=="" set "OUTDIR=.\diagnostics"
set "WITHDUMP=%~3"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

rem ---- 定位 jcmd ----
set "JCMD_CMD="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jcmd.exe" set "JCMD_CMD=%JAVA_HOME%\bin\jcmd.exe"
if "%JCMD_CMD%"=="" (
    for /f "delims=" %%i in ('where jcmd 2^>nul') do set "JCMD_CMD=%%i"
)
if "%JCMD_CMD%"=="" (
    echo [ERROR] 找不到 jcmd，请设置 JAVA_HOME 或将 JDK bin 加入 PATH
    exit /b 2
)

for /f "tokens=1-3 delims=/:. " %%a in ("%time: =0%") do set "TS=%%a%%b%%c"
set "PREFIX=%OUTDIR%\jcmd_%PID%_%TS%"

echo [INFO] pid=%PID% jcmd=%JCMD_CMD%
echo [INFO] 输出前缀: %PREFIX%

call :run VM.uptime
call :run VM.flags
call :run VM.command_line
call :run Thread.print
call :run GC.class_histogram
call :run GC.heap_info

if "%WITHDUMP%"=="--with-heap-dump" (
    echo [INFO] 采集 GC.heap_dump（可能较慢，先做 Full GC）...
    "%JCMD_CMD%" %PID% GC.heap_dump "%PREFIX%_heap.hprof"
    if errorlevel 1 echo [WARN] GC.heap_dump 失败，进程可能无权限或磁盘不足
)

echo.
echo [INFO] 完成。清单:
dir /b "%OUTDIR%\jcmd_%PID%_%TS%*"
echo [INFO] 快速判读: VM.flags 看参数是否生效; Thread.print 尾部找死锁; class_histogram 看谁占了最多内存
exit /b 0

:run
echo [INFO] >>> %~1
"%JCMD_CMD%" %PID% %~1 > "%PREFIX%_%~1.txt" 2>&1
if errorlevel 1 (
    echo [WARN] %~1 失败，输出:
    type "%PREFIX%_%~1.txt"
)
exit /b 0
