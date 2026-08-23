@echo off
set BASE=%~dp0
set CMD=javac
if exist "%BASE%..\jdk17\jdk-17.0.2\bin\javac.exe" set CMD="%BASE%..\jdk17\jdk-17.0.2\bin\javac"
if exist "%JAVA_HOME%\bin\javac.exe" set CMD="%JAVA_HOME%\bin\javac"
if not exist %CMD% (
    where javac >nul 2>nul || (
        echo [ERROR] Java compiler not found. Set JAVA_HOME or install JDK 11+.
        pause & exit /b 1
    )
)
set CLASSES=%BASE%server\build\classes\java\main
set LIBS=%BASE%server\build\libs
if not exist "%CLASSES%" mkdir "%CLASSES%"
%CMD% -encoding UTF-8 -d "%CLASSES%" -cp "%LIBS%\sqlite-jdbc-3.45.1.0.jar;%LIBS%\json-20231013.jar" "%BASE%server\src\main\java\com\knowledge\ApiServer.java" "%BASE%server\src\main\java\com\knowledge\DatabaseHelper.java" "%BASE%server\src\main\java\com\knowledge\KnowledgeServer.java"
echo Compile done.
