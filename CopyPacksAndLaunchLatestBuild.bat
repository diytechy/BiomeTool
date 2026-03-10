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
::robocopy "%SOURCE%" "%DEST%" /E ^
::    /XD ".*" "_*" "OldPromptAndReviewReferences" "Review" ^
::    /R:3 /W:5 /XO /NDL /NJH /NJS /purge


echo.
echo Copy operation completed.
echo.

:: Check if BiomeTool is already running using PowerShell (more reliable)
echo Checking if BiomeTool is already running...

powershell -command "$proc = Get-Process java -ErrorAction SilentlyContinue | Where-Object {$_.CommandLine -like '*BiomeToolEnhanced-0.5.1-all.jar*'}; if ($proc) { exit 0 } else { exit 1 }"

if !ERRORLEVEL! EQU 0 (
    echo BiomeTool is already running. Attempting to activate window...
    powershell -command "$proc = Get-Process java | Where-Object {$_.CommandLine -like '*BiomeToolEnhanced-0.5.1-all.jar*' -and $_.MainWindowTitle -ne ''}; if ($proc) { Add-Type -TypeDefinition '[DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr hWnd);' -Name Win32 -Namespace Native; [Native.Win32]::SetForegroundWindow($proc.MainWindowHandle) }"
    goto :end
)

:: Program is not running, start it
echo Starting BiomeTool...
SET "scriptPath=%~dp0"
cd "%scriptPath%build\libs"
set JavaHome="C:\JAVA\jdk-23\bin\java"
%JavaHome% -jar BiomeToolEnhanced-0.5.1-all.jar

:end
endlocal