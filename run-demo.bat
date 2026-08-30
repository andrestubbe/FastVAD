@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "MAVEN_OPTS=--enable-native-access=ALL-UNNAMED -Dorg.slf4j.simpleLogger.defaultLogLevel=warn"

echo ⚡ Installing FastVAD to local cache...
call mvn -q clean install -DskipTests
if %ERRORLEVEL% NEQ 0 ( echo [ERROR] FastVAD build failed! & pause & exit /b %ERRORLEVEL% )

echo 🚀 Running FastVAD Live Demo...
cd examples\Demo
call mvn -q compile exec:java -Dexec.mainClass=fastvad.Demo
if %ERRORLEVEL% NEQ 0 ( echo [ERROR] Demo execution failed! )
cd ..\..
pause