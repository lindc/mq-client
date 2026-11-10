@echo off
setlocal enabledelayedexpansion

rem ============================================================
rem  MQ Client Desktop - Windows 打包脚本
rem
rem  产出免安装 zip（jpackage app-image + 精简 JRE），解压后
rem  MQClient\MQClient.exe 双击即用，无需安装 JDK。
rem
rem  用法：  build.bat [额外 gradle 参数]
rem  示例：  build.bat                  正常打包（增量，最快）
rem          build.bat --rerun-tasks    强制重跑打包链
rem
rem  依赖：JDK 25（必须带 jpackage —— jpackage 只能打包当前系统，
rem  不能交叉打包；Windows 的 zip 必须在 Windows 上构建）。
rem ============================================================

rem JAVA_HOME：优先用环境变量；未设则探测本机默认安装路径
if not defined JAVA_HOME (
    if exist "C:\Development\compile\JDK25\bin\jpackage.exe" (
        set "JAVA_HOME=C:\Development\compile\JDK25"
    )
)
if not defined JAVA_HOME (
    echo [ERROR] 未找到 JDK 25：请 set JAVA_HOME=你的JDK25路径 后重试。
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
    echo [ERROR] %JAVA_HOME%\bin\jpackage.exe 不存在。
    echo         打包必须由带 jpackage 的 JDK 25 运行（compose 桌面打包
    echo         用的是运行 Gradle 的那个 JDK）。
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [INFO] JAVA_HOME=%JAVA_HOME%
java -version 2>&1 | findstr /i version

echo [INFO] 开始打包（首次构建约 1-2 分钟）...
call gradlew.bat :app-ui:zipDistributable %*
if errorlevel 1 (
    echo [ERROR] 打包失败，请检查上方 gradle 输出。
    exit /b 1
)

echo.
echo [OK] 产物：
for %%f in ("app-ui\build\compose\binaries\main\MQClient-*.zip") do (
    echo        %%~ff
    echo        大小：%%~zf 字节
)
echo.
echo 解压后运行 MQClient\MQClient.exe 即可（自带精简 JRE，无需装 JDK）。
endlocal
