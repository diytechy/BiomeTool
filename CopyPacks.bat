@echo off
setlocal enabledelayedexpansion

:: Configure paths based on computer name
set "COMPUTER=%COMPUTERNAME%"

if /i "%COMPUTER%"=="MSI" (
    set "CHIMERA_SOURCE=C:\Projects\CHIMERA"
    set "BIOMETOOL_PATH=C:\Projects\BiomeTool\build\libs\packs\CHIMERA"
    set "MINECRAFT_DIR=C:\MC\MINECRAFT_SERVER_TMP_26-1_SPARSE"
    set "WORLD_NAME=c1"
    
) else if /i "%COMPUTER%"=="DESKTOP-OFFICE" (
    set "CHIMERA_SOURCE=C:\Projects\CHIMERA"
    set "BIOMETOOL_PATH=C:\Projects\BiomeTool\build\libs\packs\CHIMERA"
    set "MINECRAFT_DIR=Z:\MC_SERV_BACKUP_20260516\MINECRAFT_SERVER_TMP_4BACKUP"
    set "WORLD_NAME=chimera"
) else (
    :: Default configuration
    set "CHIMERA_SOURCE=C:\Projects\CHIMERA"
    set "BIOMETOOL_PATH=C:\Projects\BiomeTool\build\libs\packs\CHIMERA"
    set "MINECRAFT_DIR=Z:\MC_SERV_BACKUP_20260516\MINECRAFT_SERVER_TMP_4BACKUP"
    set "WORLD_NAME=chimera"
)

:: Construct derived paths
set "MINECRAFT_PACKS=%MINECRAFT_DIR%\plugins\Terra\packs\CHIMERA"
set "REGION_DIR=%MINECRAFT_DIR%\world\dimensions\minecraft\%WORLD_NAME%\region"

:: Define source and destination folders
set "SOURCE=%CHIMERA_SOURCE%"
set "DEST=%BIOMETOOL_PATH%"

echo Starting copy operation...
echo Source: %SOURCE%
echo Destination: %DEST%
echo.

:: Copy everything recursively, excluding specified folders
robocopy "%SOURCE%" "%DEST%" /E ^
    /XD ".*" "_*" "OldPromptAndReviewReferences" "Review" "build" "gradlew" "memory" ^
    /R:3 /W:5 /NDL /NJH /NJS /purge

:: Delete all files from destination root EXCEPT the three we want
echo.
echo Cleaning up root files...
for %%F in ("%DEST%\*.*") do (
    set "filename=%%~nxF"
    if /i not "!filename!"=="pack.yml" (
        if /i not "!filename!"=="meta.yml" (
            if /i not "!filename!"=="customization.yml" (
				if /i not "!filename!"=="substratum_meta.yml" (
					echo Deleting: !filename!
					del "%%F"
				)
            )
        )
    )
)

set "SOURCE=%BIOMETOOL_PATH%"
set "DEST=%MINECRAFT_PACKS%"
echo Destination: %DEST%
echo.

:: Copy everything recursively, excluding specified folders
robocopy "%SOURCE%" "%DEST%" /E ^
    /R:3 /W:5 /NDL /NJH /NJS /purge


echo.
echo Compressing CHIMERA folder...
set "CHIMERA_DIR=%MINECRAFT_PACKS%"
set "CHIMERA_ZIP=%MINECRAFT_DIR%\plugins\Terra\packs\CHIMERA.zip"
if exist "%CHIMERA_ZIP%" del "%CHIMERA_ZIP%"
powershell -NoProfile -Command "Add-Type -Assembly System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::CreateFromDirectory('%CHIMERA_DIR%', '%CHIMERA_ZIP%', [System.IO.Compression.CompressionLevel]::NoCompression, $false)"
echo CHIMERA.zip created.

echo.
echo Clearing region files...
if exist "%REGION_DIR%" (
    del /Q "%REGION_DIR%\*.*"
    echo Region files deleted.
) else (
    echo Region folder not found, skipping.
)

echo.
echo Copy operation completed.
echo.

endlocal