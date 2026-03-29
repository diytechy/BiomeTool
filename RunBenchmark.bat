@echo off
setlocal

call CopyPacks.bat

:: BiomeTool Benchmark Script
:: Usage: RunBenchmark.bat [tilesX] [tilesY] [seed]
:: Defaults: 100x100 tiles, seed 1
::
:: Examples:
::   RunBenchmark.bat              (100x100 tiles, seed 1)
::   RunBenchmark.bat 50           (50x50 tiles, seed 1)
::   RunBenchmark.bat 100 100 42   (100x100 tiles, seed 42)

set "TILES_X=%~1"
set "TILES_Y=%~2"
set "SEED=%~3"

if "%TILES_X%"=="" set "TILES_X=35"
if "%TILES_Y%"=="" set "TILES_Y=%TILES_X%"
if "%SEED%"=="" set "SEED=1"

set "SCRIPT_DIR=%~dp0"
set "JAR_DIR=%SCRIPT_DIR%build\libs"
set "JAR=%JAR_DIR%\BiomeToolEnhanced-0.5.1-all.jar"

if not exist "%JAR%" (
    echo ERROR: JAR not found at %JAR%
    echo Please build the project first with: gradlew build
    exit /b 1
)

set "JAVA_HOME_DIR=C:\JAVA\jdk-23\bin"
if exist "%JAVA_HOME_DIR%\java.exe" (
    set "JAVA=%JAVA_HOME_DIR%\java"
) else (
    set "JAVA=java"
)

echo Launching benchmark with %TILES_X%x%TILES_Y% tiles, seed %SEED%...
echo.

cd "%JAR_DIR%"
%JAVA% --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -cp "%JAR%" com.dfsek.terra.biometool.BiomeBenchmark %TILES_X% %TILES_Y% %SEED%

echo.
pause
endlocal
