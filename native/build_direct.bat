@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"
cd /d "C:\Users\andre\Documents\2026-08-17-Work-FastJava\FastVAD\native"
cl.exe /O2 /LD /std:c++17 /EHsc /I"C:\Program Files\java\jdk-21.0.12.1\include" /I"C:\Program Files\java\jdk-21.0.12.1\include\win32" src\fastvad_native.cpp /Fe:fastvad.dll