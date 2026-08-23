@echo off
set BASE=%~dp0

:: Load local-only overrides (KNOWLEDGE_KB_ROOT etc.). Not committed to git.
if exist "%BASE%config.local.bat" call "%BASE%config.local.bat"

:: 1. JAVA_HOME from system environment
if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\java.exe" goto :set_from_home
)

:: 2. java from PATH
where java >nul 2>nul
if not errorlevel 1 set JAVA_EXE=java& goto :run

:: 3. Bundled JDK (relative to project, portable)
set _BUNDLED=%BASE%..\jdk17\jdk-17.0.2
if exist "%_BUNDLED%\bin\java.exe" set JAVA_EXE=%_BUNDLED%\bin\java& goto :run

:: 4. Common default locations
for %%d in (
    "%ProgramFiles%\Java\jdk-17*"
    "%ProgramFiles%\Java\jdk-11*"
    "%ProgramFiles%\Eclipse Adoptium\jdk-17*"
    "%LocalAppData%\Programs\Eclipse Adoptium\jdk-17*"
) do if exist "%%~d\bin\java.exe" set JAVA_EXE=%%~d\bin\java& goto :run

echo [ERROR] Java not found. Set JAVA_HOME environment variable or install JDK 11+.
pause
exit /b 1

:set_from_home
set JAVA_EXE=%JAVA_HOME%\bin\java

:run
set CP=%BASE%server\build\classes\java\main
set CP=%CP%;%BASE%server\build\libs\sqlite-jdbc-3.45.1.0.jar
set CP=%CP%;%BASE%server\build\libs\json-20231013.jar
set CP=%CP%;%BASE%server\build\libs\slf4j-api-2.0.9.jar
set CP=%CP%;%BASE%server\build\libs\slf4j-simple-2.0.9.jar
"%JAVA_EXE%" -cp "%CP%" com.knowledge.KnowledgeServer 8080
