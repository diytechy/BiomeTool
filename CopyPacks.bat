@echo off
setlocal enabledelayedexpansion

:: Define source and destination folders
set "SOURCE=C:\Projects\ORIGEN2"
set "DEST=C:\Projects\BiomeTool\build\libs\packs\terra-origen"

echo Starting copy operation...
echo Source: %SOURCE%
echo Destination: %DEST%
echo.

:: Copy everything recursively, excluding specified folders
robocopy "%SOURCE%" "%DEST%" /E ^
    /XD ".*" "_*" "OldPromptAndReviewReferences" "Review" ^
    /R:3 /W:5 /XO /NDL /NJH /NJS /purge

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

set "SOURCE=C:\Projects\BiomeTool\build\libs\packs\terra-origen"
set "DEST=C:\MC\MINECRAFT_SERVER_TMP_4BACKUP\plugins\Terra\packs\CHIMERA"
echo Destination: %DEST%
echo.

:: Copy everything recursively, excluding specified folders
robocopy "%SOURCE%" "%DEST%" /E ^
    /XD ".*" "_*" "OldPromptAndReviewReferences" "Review" ^
    /R:3 /W:5 /XO /NDL /NJH /NJS /purge


echo.
echo Copy operation completed.
echo.

endlocal