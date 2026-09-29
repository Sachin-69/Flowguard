@echo off
REM Run FlowGuard on http://localhost:8080
call build.bat
if %errorlevel% neq 0 exit /b 1
java -cp out com.flowguard.FlowGuardApp
