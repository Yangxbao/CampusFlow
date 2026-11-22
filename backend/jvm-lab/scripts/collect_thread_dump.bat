@echo off
REM ============================================================
REM  collect_thread_dump.bat - 采集 Java 进程线程转储(Thread Dump)
REM
REM  用法:
REM    collect_thread_dump.bat <pid> [输出目录]
REM  示例:
REM    collect_thread_dump.bat 12345 .\diagnostics
REM
REM  说明:
REM    1. 连续采集 3 份、间隔 2 秒（单份快照无法判断锁是否在移动，
REM       多份对比才能确认线程长期 BLOCKED / WAITING，定位死锁与线程泄漏）;
REM    2. 依赖 JDK 的 jstack；优先用 %JAVA_HOME%\bin\jstack，
REM       否则从 PATH 查找（jstack 版本须与目标 JVM 主版本一致）;
REM    3. 若无权限，Windows 下以同用户/管理员身份运行；
REM    4. 替代方案: jcmd <pid> Thread.print 输出完全等价（推荐）。
REM ============================================================
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo [ERROR] 用法: %~nx0 ^<pid^> [输出目录]
    echo         先用 jps -l 或 tasklist 找到目标 Java 进程 pid
    exit /b 1
)

set "PID=%~1"
set "OUTDIR=%~2"
if "%OUTDIR%"=="" set "OUTDIR=.\diagnostics"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

rem ---- 定位 jstack ----
set "JSTACK_CMD="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jstack.exe" set "JSTACK_CMD=%JAVA_HOME%\bin\jstack.exe"
if "%JSTACK_CMD%"=="" (
    for /f "delims=" %%i in ('where jstack 2^>nul') do set "JSTACK_CMD=%%i"
)
if "%JSTACK_CMD%"=="" (
    echo [ERROR] 找不到 jstack，请设置 JAVA_HOME 或将 JDK bin 加入 PATH
    exit /b 2
)

rem ---- 时间戳（形如 20261121_004233）----
for /f "tokens=1-4 delims=/:. " %%a in ("%date%") do set "DS=%%a%%b%%c"
for /f "tokens=1-3 delims=/:. " %%a in ("%time: =0%") do set "TS=%%a%%b%%c"

echo [INFO] pid=%PID%  jstack=%JSTACK_CMD%
echo [INFO] 输出目录: %OUTDIR%

for %%n in (1 2 3) do (
    set "FILE=%OUTDIR%\threaddump_%PID%_%DS%_!TS!_%%n.txt"
    echo [INFO] 采集第 %%n 份: !FILE!
    "%JSTACK_CMD%" -l %PID% > "!FILE!" 2>&1
    if errorlevel 1 (
        echo [ERROR] jstack 执行失败，请检查 pid 是否存在/权限是否足够
        type "!FILE!"
        exit /b 3
    )
    rem 间隔 2 秒
    ping -n 3 127.0.0.1 > nul
)

echo [INFO] 完成。3 份线程转储已保存到 %OUTDIR%
echo [INFO] 死锁检查: 在文件末尾查找 "Found one Java-level deadlock"
exit /b 0
