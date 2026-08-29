@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ===================================================
echo  FastVAD 0.1.0 — 120-Column Interactive Hero Demo
echo ===================================================
call mvn compile exec:java -Dexec.mainClass=fastvad.Demo
pause