@echo off
title Build - Kaer Morhen Save Vault
set "JDK=C:\Program Files\Java\jdk-21.0.12.1\bin"
cd /d "%~dp0"

if not exist "%JDK%\javac.exe" (
    echo [ERROR] JDK not found at: %JDK%
    pause
    exit /b 1
)

echo Cleaning...
if exist build rmdir /s /q build
mkdir build

echo Compiling...
"%JDK%\javac.exe" -encoding UTF-8 -d build src\kaermorhen\*.java
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo Copying resources...
xcopy /e /i /y /q "src\kaermorhen\res" "build\kaermorhen\res" >nul

echo Creating jar...
"%JDK%\jar.exe" cfe KaerMorhenSaveVault.jar kaermorhen.Main -C build .
if errorlevel 1 (
    echo [ERROR] Jar creation failed.
    pause
    exit /b 1
)

echo.
echo Done! KaerMorhenSaveVault.jar created.
pause
