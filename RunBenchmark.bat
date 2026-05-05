@echo off
setlocal

call CopyPacks.bat

:: BiomeTool Benchmark Script
:: Usage: RunBenchmark.bat [tilesX] [tilesY] [seed] [skipPause] [subsample] [lod] [threads]
:: Defaults: 200x200 tiles, seed 1, subsample 4, lod 0, threads 4
::
:: subsample and lod mirror the UI: world area per tile = 128 * subsample (blocks),
:: lod halves pixel count and doubles stride, matching TerraBiomeImageGenerator exactly.
::
:: threads: number of concurrent worker threads (default 4).
::          The tile grid is split into that many equal snake-path segments processed in parallel.
::
:: Examples:
::   RunBenchmark.bat                        (200x200 tiles, seed 1, subsample 4, lod 0, 4 threads)
::   RunBenchmark.bat 50                     (50x50 tiles, seed 1, subsample 4, lod 0, 4 threads)
::   RunBenchmark.bat 100 100 42             (100x100 tiles, seed 42, subsample 4, lod 0, 4 threads)
::   RunBenchmark.bat 35 35 1 0 1            (35x35 tiles, seed 1, subsample 1, lod 0, 4 threads)
::   RunBenchmark.bat 35 35 1 0 4 2          (35x35 tiles, seed 1, subsample 4, lod 2, 4 threads)
::   RunBenchmark.bat 200 200 1 0 4 0 8      (200x200 tiles, seed 1, subsample 4, lod 0, 8 threads)
::   RunBenchmark.bat 200 200 1 0 4 0 1      (single-threaded, for comparison)

set "TILES_X=%~1"
set "TILES_Y=%~2"
set "SEED=%~3"
set "SKIP_PAUSE=%~4"
set "SUBSAMPLE=%~5"
set "LOD=%~6"
set "THREADS=%~7"

if "%TILES_X%"==""   set "TILES_X=200"
if "%TILES_Y%"==""   set "TILES_Y=%TILES_X%"
if "%SEED%"==""      set "SEED=1"
if "%SKIP_PAUSE%"="" set "SKIP_PAUSE=0"
if "%SUBSAMPLE%"=""  set "SUBSAMPLE=4"
if "%LOD%"==""       set "LOD=0"
if "%THREADS%"==""   set "THREADS=4"

set "SCRIPT_DIR=%~dp0"
set "JAR_DIR=%SCRIPT_DIR%build\libs"
set "JAR=%JAR_DIR%\BiomeToolEnhanced-0.5.1-all.jar"

if not exist "%JAR%" (
    echo ERROR: JAR not found at %JAR%
    echo Please build the project first with: gradlew build
    exit /b 1
)

set "JAVA_HOME_DIR=C:\JAVA\jdk-25.0.1\bin"
if exist "%JAVA_HOME_DIR%\java.exe" (
    set "JAVA=%JAVA_HOME_DIR%\java"
) else (
    set "JAVA=java"
)

:: CSV output path - pack name is appended by the tool at runtime
:: Pattern: benchmark_{W}x{H}_seed{S}_{PACK}.csv saved to project root
set "CSV_PREFIX=%SCRIPT_DIR%benchmark_%TILES_X%x%TILES_Y%_seed%SEED%_"

echo Launching benchmark with %TILES_X%x%TILES_Y% tiles, seed %SEED%, subsample %SUBSAMPLE%x, lod %LOD%, %THREADS% thread(s)...
echo.

cd "%JAR_DIR%"
%JAVA% --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -cp "%JAR%" com.dfsek.terra.biometool.BiomeBenchmark %TILES_X% %TILES_Y% %SEED% "%CSV_PREFIX%" %SUBSAMPLE% %LOD% %THREADS%

echo.
if "%SKIP_PAUSE%"=="1" goto end

pause

:end
endlocal
