@echo off
REM Build FlowGuard (pure JDK, no Maven needed)
if not exist out mkdir out
javac -encoding UTF-8 -d out src\com\flowguard\*.java
if %errorlevel% neq 0 ( echo BUILD FAILED & exit /b 1 )
echo BUILD OK - classes in out\
