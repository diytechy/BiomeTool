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
:: Note: For wildcard exclusions, just use the pattern without full path
robocopy "%SOURCE%" "%DEST%" /E ^
    /XD ".*" "_*" "OldPromptAndReviewReferences" "Review" ^
    /R:3 /W:5

:: Delete all files from destination root EXCEPT the three we want
echo.
echo Cleaning up root files...
for %%F in ("%DEST%\*.*") do (
    set "filename=%%~nxF"
    if /i not "!filename!"=="pack.yml" (
        if /i not "!filename!"=="meta.yml" (
            if /i not "!filename!"=="customization.yml" (
                echo Deleting: !filename!
                del "%%F"
            )
        )
    )
)

echo.
echo Copy operation completed.
echo.

endlocal

SET "scriptPath=%~dp0"
set JavaHome="C:\JAVA\jdk-23\bin\java"

cd "%~dp0build\libs"

::%JavaHome% --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -jar BiomeTool-0.4.9-win.jar
%JavaHome% -jar BiomeToolEnhanced-0.5.1-all.jar