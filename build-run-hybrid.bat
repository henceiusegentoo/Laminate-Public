@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT_DIR=%~dp0"
set "ROOT_DIR=%ROOT_DIR:~0,-1%"
set "SERVER_LIBS_DIR=%ROOT_DIR%\paper-server\build\libs"
set "PLUGINS_DIR=%SERVER_LIBS_DIR%\plugins"
set "PLUGIN_LIBS_DIR=%ROOT_DIR%\test-plugin\build\libs"
set "SERVER_JAR=laminate-server-1.21.11-R0.1-SNAPSHOT.jar"
set "DRY_RUN=0"
if /I "%~1"=="--dry-run" set "DRY_RUN=1"
if not exist "%ROOT_DIR%\gradlew.bat" (
  echo [ERROR] gradlew.bat nicht gefunden: %ROOT_DIR%
  exit /b 1
)
echo [1/4] Baue Laminate Server JAR (createLaminateServerJar)...
call "%ROOT_DIR%\gradlew.bat" -p "%ROOT_DIR%" :paper-server:createLaminateServerJar
if errorlevel 1 (
  echo [ERROR] Schritt 1 fehlgeschlagen.
  exit /b 1
)
echo [2/4] Baue Plugin als shadowJar...
call "%ROOT_DIR%\gradlew.bat" -p "%ROOT_DIR%" :test-plugin:shadowJar
if errorlevel 1 (
  echo [ERROR] Schritt 2 fehlgeschlagen.
  exit /b 1
)
echo [3/4] Kopiere shadowJar nach %PLUGINS_DIR%...
if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%"
if errorlevel 1 (
  echo [ERROR] Plugins-Ordner konnte nicht erstellt werden.
  exit /b 1
)
set "LATEST_PLUGIN_JAR="
for /f "delims=" %%F in ('dir /b /o-d "%PLUGIN_LIBS_DIR%\*-all.jar" 2^>nul') do (
  set "LATEST_PLUGIN_JAR=%PLUGIN_LIBS_DIR%\%%F"
  goto :copy_plugin
)
echo [ERROR] Kein shadowJar gefunden in %PLUGIN_LIBS_DIR% (erwartet: *-all.jar)
exit /b 1
:copy_plugin
copy /Y "%LATEST_PLUGIN_JAR%" "%PLUGINS_DIR%\"
if errorlevel 1 (
  echo [ERROR] Plugin konnte nicht nach %PLUGINS_DIR% kopiert werden.
  exit /b 1
)
echo [INFO] Kopiert: %LATEST_PLUGIN_JAR%
echo [4/4] Starte Laminate Server aus %SERVER_LIBS_DIR%...
if not exist "%SERVER_LIBS_DIR%\%SERVER_JAR%" (
  echo [ERROR] Server-Jar nicht gefunden: %SERVER_LIBS_DIR%\%SERVER_JAR%
  exit /b 1
)
if "%DRY_RUN%"=="1" (
  echo [DRY-RUN] cd /d "%SERVER_LIBS_DIR%"
  echo [DRY-RUN] java -jar "%SERVER_JAR%" nogui
  echo [DONE] Dry-Run abgeschlossen.
  exit /b 0
)
cd /d "%SERVER_LIBS_DIR%"
java -jar "%SERVER_JAR%" nogui
