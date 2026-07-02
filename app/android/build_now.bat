@echo off
set JAVA_HOME=D:\Java
cd /d D:\visus\app\android
call gradlew.bat assembleDebug
echo.
echo BUILD COMPLETE
echo Check: D:\visus\app\android\app\build\outputs\apk\debug\
pause
