@echo off
REM ============================================================
REM  collect_heap_dump.bat - 采集 Java 进程堆转储(Heap Dump / .hprof)
REM
REM  用法:
REM    collect_heap_dump.bat <pid> [输出目录] [live|all]
REM  示例:
REM    collect_heap_dump.bat 12345 .\diagnostics live
REM
REM  jmap -dump 写法注意(重要):
REM    1. 标准写法是长选项形式:
REM         jmap -dump:live,format=b,file=heap.hprof <pid>
REM       (旧写法 jmap -dump:format=b,file=... live <pid> 在 JDK8+ 已不可靠，勿混用)
REM    2. :live 表示只转储"存活对象"，但会先触发一次 Full GC ——
REM       大堆/高负载生产服务上可能造成秒级 STW，抓 dump 前先评估；
REM       抓"全量含垃圾对象"用 all(不触发 Full GC，dump 更大)；
REM    3. -F 强制模式仅在 Windows 上针对无响应进程（Linux 上 -F 已随 SA 逐步废弃），
REM       进程僵死优先尝试 jcmd <pid> GC.heap_dump；
REM    4. 文件很大(约等于堆占用)，确保磁盘余量 > maxMemory；分析用 Eclipse MAT / VisualVM。
REM ============================================================
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo [ERROR] 用法: %~nx0 ^<pid^> [输出目录] [live^|all]
    exit /b 1
)

set "PID=%~1"
set "OUTDIR=%~2"
if "%OUTDIR%"=="" set "OUTDIR=.\diagnostics"
set "MODE=%~3"
if "%MODE%"=="" set "MODE=live"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

rem ---- 定位 jmap ----
set "JMAP_CMD="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jmap.exe" set "JMAP_CMD=%JAVA_HOME%\bin\jmap.exe"
if "%JMAP_CMD%"=="" (
    for /f "delims=" %%i in ('where jmap 2^>nul') do set "JMAP_CMD=%%i"
)
if "%JMAP_CMD%"=="" (
    echo [ERROR] 找不到 jmap，请设置 JAVA_HOME 或将 JDK bin 加入 PATH
    exit /b 2
)

for /f "tokens=1-3 delims=/:. " %%a in ("%time: =0%") do set "TS=%%a%%b%%c"
set "FILE=%OUTDIR%\heap_%PID%_%MODE%_%TS%.hprof"

echo [INFO] pid=%PID% mode=%MODE% jmap=%JMAP_CMD%
echo [INFO] 目标文件: %FILE%
echo [INFO] 大堆进程采集可能耗时数十秒甚至更久，请耐心等待...

if "%MODE%"=="live" (
    "%JMAP_CMD%" -dump:live,format=b,file="%FILE%" %PID%
) else (
    "%JMAP_CMD%" -dump:format=b,file="%FILE%" %PID%
)
if errorlevel 1 (
    echo [ERROR] jmap 执行失败。可改用: jcmd %PID% GC.heap_dump "%FILE%"
    exit /b 3
)

echo [INFO] 完成: %FILE%
echo [INFO] 后续分析: Eclipse MAT 打开该 .hprof，看 Leak Suspects / Dominator Tree
exit /b 0
