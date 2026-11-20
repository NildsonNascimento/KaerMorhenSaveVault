@echo off
set "JDK=C:\Program Files\Java\jdk-21.0.12.1\bin"
cd /d "%~dp0"
if not exist KaerMorhenSaveVault.jar call build.bat
start "" "%JDK%\javaw.exe" -jar KaerMorhenSaveVault.jar
