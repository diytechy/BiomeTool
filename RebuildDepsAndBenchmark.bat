@echo off
setlocal enabledelayedexpansion

:: ============================================================
::  RebuildDepsAndBenchmark.bat
::  Rebuilds the Terra dependency chain and runs the BiomeTool
::  benchmark. Use flags to control which components rebuild.
::
::  Usage:
::    RebuildDepsAndBenchmark.bat [options]
::
::  Options:
::    --tectonic     Rebuild Tectonic and publish to mavenLocal
::    --terra        Rebuild Terra and publish to mavenLocal
::    --biometool    Rebuild BiomeTool (required if Terra/Tectonic
::                   were republished for the first time this session)
::    --all          Rebuild all three + run benchmark
::    --bench-only   Skip all rebuilds, only run benchmark
::    --no-bench     Rebuild requested components but skip benchmark
::
::  Examples:
::    RebuildDepsAndBenchmark.bat --tectonic --terra --biometool
::    RebuildDepsAndBenchmark.bat --all
::    RebuildDepsAndBenchmark.bat --terra --biometool
::    RebuildDepsAndBenchmark.bat --bench-only
::
::  Notes:
::    - ORIGEN2/Chimera pack changes do NOT need a rebuild.
::      CopyPacks.bat (called by RunBenchmark.bat) deploys pack
::      changes automatically.
::    - Tectonic must be rebuilt before Terra if both changed.
::    - BiomeTool must be rebuilt if Terra or Tectonic was
::      republished to mavenLocal.
:: ============================================================

set "TECTONIC_DIR=C:\Projects\Tectonic"
set "TERRA_DIR=C:\Projects\Terra"
set "BIOMETOOL_DIR=C:\Projects\BiomeTool"
set "JAVA_HOME_DIR=C:\JAVA\jdk-25.0.1\bin"

set "DO_TECTONIC=0"
set "DO_TERRA=0"
set "DO_BIOMETOOL=0"
set "DO_BENCH=1"

:: Parse arguments
:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--all"        set DO_TECTONIC=1 & set DO_TERRA=1 & set DO_BIOMETOOL=1
if /i "%~1"=="--tectonic"   set DO_TECTONIC=1
if /i "%~1"=="--terra"      set DO_TERRA=1
if /i "%~1"=="--biometool"  set DO_BIOMETOOL=1
if /i "%~1"=="--bench-only" set DO_TECTONIC=0 & set DO_TERRA=0 & set DO_BIOMETOOL=0
if /i "%~1"=="--no-bench"   set DO_BENCH=0
shift
goto parse_args
:args_done

:: If no build flags and not bench-only, print usage
if "%DO_TECTONIC%%DO_TERRA%%DO_BIOMETOOL%%DO_BENCH%"=="0001" (
    echo No rebuild flags specified. Running benchmark only.
    echo Use --help or open this script to see usage.
    echo.
)

echo ============================================================
echo  Terra Optimization Rebuild ^& Benchmark
echo ============================================================
echo  Tectonic rebuild : %DO_TECTONIC%
echo  Terra rebuild    : %DO_TERRA%
echo  BiomeTool rebuild: %DO_BIOMETOOL%
echo  Run benchmark    : %DO_BENCH%
echo ============================================================
echo.

:: ---- STEP 1: Tectonic ----
if "%DO_TECTONIC%"=="1" (
    echo [1/4] Building Tectonic and publishing to mavenLocal...
    echo       Source: %TECTONIC_DIR%
    pushd "%TECTONIC_DIR%"
    call gradlew.bat publishToMavenLocal
    if errorlevel 1 (
        echo.
        echo ERROR: Tectonic build failed. Aborting.
        popd
        exit /b 1
    )
    popd
    echo [1/4] Tectonic published to mavenLocal successfully.
    echo.
) else (
    echo [1/4] Skipping Tectonic rebuild.
)

:: ---- STEP 2: Terra ----
if "%DO_TERRA%"=="1" (
    echo [2/4] Building Terra and publishing to mavenLocal...
    echo       Source: %TERRA_DIR%
    pushd "%TERRA_DIR%"
    call gradlew.bat publishToMavenLocal
    if errorlevel 1 (
        echo.
        echo ERROR: Terra build failed. Aborting.
        popd
        exit /b 1
    )
    popd
    echo [2/4] Terra published to mavenLocal successfully.
    echo.
) else (
    echo [2/4] Skipping Terra rebuild.
)

:: ---- STEP 3: BiomeTool ----
if "%DO_BIOMETOOL%"=="1" (
    echo [3/4] Building BiomeTool...
    echo       Source: %BIOMETOOL_DIR%
    pushd "%BIOMETOOL_DIR%"
    call gradlew.bat build
    if errorlevel 1 (
        echo.
        echo ERROR: BiomeTool build failed. Aborting.
        popd
        exit /b 1
    )
    popd
    echo [3/4] BiomeTool built successfully.
    echo.
) else (
    echo [3/4] Skipping BiomeTool rebuild.
)

:: ---- STEP 4: Benchmark ----
if "%DO_BENCH%"=="1" (
    echo [4/4] Running benchmark...
    echo       (CopyPacks.bat will deploy ORIGEN2 pack automatically)
    echo.
    call "%BIOMETOOL_DIR%\RunBenchmark.bat"
    echo.
    echo Benchmark complete. Results:
    for %%F in ("%BIOMETOOL_DIR%\benchmark_*.csv") do (
        echo   %%F
    )
) else (
    echo [4/4] Skipping benchmark ^(--no-bench specified^).
)

echo.
echo ============================================================
echo  Done.
echo ============================================================
endlocal
