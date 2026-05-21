@echo off
setlocal enabledelayedexpansion

:: Define source and destination folders
set "SOURCE=C:\Projects\CHIMERA"
set "DEST=C:\Projects\BiomeTool\build\libs\packs\CHIMERA"

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

set "SOURCE=C:\Projects\BiomeTool\build\libs\packs\CHIMERA"
set "DEST=Z:\MC_SERV_BACKUP_20260516\MINECRAFT_SERVER_TMP_4BACKUP\plugins\Terra\packs\CHIMERA"
echo Destination: %DEST%
echo.

:: Copy everything recursively, excluding specified folders
robocopy "%SOURCE%" "%DEST%" /E ^
    /XD ".*" "_*" "OldPromptAndReviewReferences" "Review" "build" "gradlew" "memory" ^
    /R:3 /W:5 /NDL /NJH /NJS /purge


echo.
echo Compressing CHIMERA folder...
set "CHIMERA_DIR=Z:\MC_SERV_BACKUP_20260516\MINECRAFT_SERVER_TMP_4BACKUP\plugins\Terra\packs\CHIMERA"
set "CHIMERA_ZIP=Z:\MC_SERV_BACKUP_20260516\MINECRAFT_SERVER_TMP_4BACKUP\plugins\Terra\packs\CHIMERA.zip"
if exist "%CHIMERA_ZIP%" del "%CHIMERA_ZIP%"
powershell -NoProfile -Command "Add-Type -Assembly System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::CreateFromDirectory('%CHIMERA_DIR%', '%CHIMERA_ZIP%', [System.IO.Compression.CompressionLevel]::NoCompression, $false)"
echo CHIMERA.zip created.

echo.
echo Clearing region files...
set "REGION_DIR=Z:\MC_SERV_BACKUP_20260516\MINECRAFT_SERVER_TMP_4BACKUP\world\dimensions\minecraft\chimera\region"
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