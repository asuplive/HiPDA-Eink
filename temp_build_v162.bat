@echo off
set JAVA_HOME=C:\_jdk17\jdk-17.0.20.1+1
set ANDROID_HOME=C:\android-sdk
set GRADLE_USER_HOME=C:\gradle-home
cd /d C:\hipda-build
C:\gradle-8.9\bin\gradle.bat assembleDebug --offline > C:\hipda-build\build_v162.txt 2>&1
echo BUILD_EXIT=%ERRORLEVEL% >> C:\hipda-build\build_v162.txt
