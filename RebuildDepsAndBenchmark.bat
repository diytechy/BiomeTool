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
::    --tectonic           Rebuild Tectonic and publish to mavenLocal
::    --terra              Rebuild Terra:
::                           1. Compile (verify only, no publish)
::                           2. Commit changes (requires --commit-msg)
::                           3. Recompile and publish to mavenLocal
::                           4. Update terraGitHash in BiomeTool build.gradle.kts
::    --commit-msg "..."   Commit message for Terra git commit (required with --terra)
::    --biometool          Rebuild BiomeTool JAR
::    --all                Rebuild all three + run benchmark (requires --commit-msg)
::    --bench-only         Skip all rebuilds, only run benchmark
::    --no-bench           Rebuild requested components but skip benchmark
::
::  Examples:
::    RebuildDepsAndBenchmark.bat --terra --biometool --commit-msg "Fix stream allocation in hot path"
::    RebuildDepsAndBenchmark.bat --all --commit-msg "Optimization: remove dead HashMap"
::    RebuildDepsAndBenchmark.bat --tectonic --terra --biometool --commit-msg "Update Tectonic dep"
::    RebuildDepsAndBenchmark.bat --bench-only
::
::  Notes:
::    - ORIGEN2/Chimera pack changes do NOT need a rebuild.
::      CopyPacks.bat (called by RunBenchmark.bat) deploys pack changes automatically.
::    - Tectonic must be rebuilt before Terra if both changed.
::    - BiomeTool must be rebuilt if Terra or Tectonic was republished to mavenLocal.
::    - Terra is versioned by its git short hash. The script commits Terra changes,
::      then uses the resulting hash to publish and update BiomeTool's dependency reference.
::    - Any build failure aborts the entire script with a non-zero exit code.
:: ============================================================

set "TECTONIC_DIR=C:\Projects\Tectonic"
set "TERRA_DIR=C:\Projects\Terra"
set "BIOMETOOL_DIR=C:\Projects\BiomeTool"
set "BIOMETOOL_GRADLE=%BIOMETOOL_DIR%\build.gradle.kts"

set "DO_TECTONIC=0"
set "DO_TERRA=0"
set "DO_BIOMETOOL=0"
set "DO_BENCH=1"
set "COMMIT_MSG="

:: Parse arguments
:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--all"         set DO_TECTONIC=1 & set DO_TERRA=1 & set DO_BIOMETOOL=1
if /i "%~1"=="--tectonic"    set DO_TECTONIC=1
if /i "%~1"=="--terra"       set DO_TERRA=1
if /i "%~1"=="--biometool"   set DO_BIOMETOOL=1
if /i "%~1"=="--bench-only"  set DO_TECTONIC=0 & set DO_TERRA=0 & set DO_BIOMETOOL=0
if /i "%~1"=="--no-bench"    set DO_BENCH=0
if /i "%~1"=="--commit-msg"  set "COMMIT_MSG=%~2" & shift
shift
goto parse_args
:args_done

:: Validate: --terra requires --commit-msg
if "%DO_TERRA%"=="1" (
    if "!COMMIT_MSG!"=="" (
        echo ERROR: --terra requires --commit-msg "your message"
        echo Example: RebuildDepsAndBenchmark.bat --terra --biometool --commit-msg "Fix A1 stream allocation"
        exit /b 1
    )
)

echo ============================================================
echo  Terra Optimization Rebuild ^& Benchmark
echo ============================================================
echo  Tectonic rebuild : %DO_TECTONIC%
echo  Terra rebuild    : %DO_TERRA%
if "%DO_TERRA%"=="1" echo  Commit message   : !COMMIT_MSG!
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

:: ---- STEP 2: Terra (4-phase) ----
if "%DO_TERRA%"=="1" (
    echo [2/4] Terra rebuild ^(4 phases^)...
    echo       Source: %TERRA_DIR%
    echo.

    :: Phase 2a: Compile only — verify the build is clean before committing
    echo   [2a] Compiling Terra ^(verify only, no publish^)...
    pushd "%TERRA_DIR%"
    call gradlew.bat build
    if errorlevel 1 (
        echo.
        echo ERROR: Terra compilation failed. Fix build errors before committing. Aborting.
        popd
        exit /b 1
    )
    echo   [2a] Terra compiled successfully.
    echo.

    :: Phase 2b: Commit changes
    echo   [2b] Committing Terra changes...
    git -C "%TERRA_DIR%" add -A
    git -C "%TERRA_DIR%" diff --cached --quiet
    if errorlevel 1 (
        git -C "%TERRA_DIR%" commit -m "!COMMIT_MSG!"
        if errorlevel 1 (
            echo.
            echo ERROR: git commit failed. Aborting.
            popd
            exit /b 1
        )
        echo   [2b] Changes committed.
    ) else (
        echo   [2b] WARNING: Nothing staged to commit. Were changes already committed?
        echo        Proceeding with current HEAD hash.
    )
    echo.

    :: Phase 2c: Capture new short hash and publish to mavenLocal
    for /f "delims=" %%H in ('git -C "%TERRA_DIR%" rev-parse --short HEAD') do set "TERRA_HASH=%%H"
    echo   [2c] Publishing Terra to mavenLocal ^(hash: !TERRA_HASH!^)...
    call gradlew.bat publishToMavenLocal
    if errorlevel 1 (
        echo.
        echo ERROR: Terra publishToMavenLocal failed. Aborting.
        popd
        exit /b 1
    )
    popd
    echo   [2c] Terra published to mavenLocal successfully.
    echo.

    :: Phase 2d: Update terraGitHash in BiomeTool build.gradle.kts
    echo   [2d] Updating terraGitHash in BiomeTool build.gradle.kts to !TERRA_HASH!...
    powershell -NoProfile -Command ^
        "(Get-Content '%BIOMETOOL_GRADLE%' -Raw) -replace 'val terraGitHash = \"[^\"]*\"', 'val terraGitHash = \"!TERRA_HASH!\"' | Set-Content '%BIOMETOOL_GRADLE%' -NoNewline"
    if errorlevel 1 (
        echo.
        echo ERROR: Failed to update terraGitHash in build.gradle.kts. Aborting.
        exit /b 1
    )
    echo   [2d] terraGitHash updated to !TERRA_HASH! in build.gradle.kts.
    echo.

    echo [2/4] Terra rebuild complete ^(hash: !TERRA_HASH!^).
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
