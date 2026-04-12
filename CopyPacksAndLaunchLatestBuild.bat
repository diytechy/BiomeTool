@echo off
setlocal enabledelayedexpansion

echo Copying packs...
call CopyPacks.bat

:: Check if BiomeTool is already running using PowerShell (more reliable)
echo Checking if BiomeTool is already running...

powershell -command "$proc = Get-Process java -ErrorAction SilentlyContinue | Where-Object {$_.CommandLine -like '*BiomeToolEnhanced-0.5.1-all.jar*'}; if ($proc) { exit 0 } else { exit 1 }"

if %ERRORLEVEL% EQU 0 (
    echo BiomeTool is already running. Attempting to activate window...
    powershell -command "$proc = Get-Process java | Where-Object {$_.CommandLine -like '*BiomeToolEnhanced-0.5.1-all.jar*' -and $_.MainWindowTitle -ne ''}; if ($proc) { Add-Type -TypeDefinition '[DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr hWnd);' -Name Win32 -Namespace Native; [Native.Win32]::SetForegroundWindow($proc.MainWindowHandle) }"
    goto :end
)

:: Program is not running, start it
echo Starting BiomeTool...
SET "scriptPath=%~dp0"
cd "%scriptPath%build\libs"
set JavaHome="C:\JAVA\jdk-25.0.1\bin\java"
%JavaHome% -jar BiomeToolEnhanced-0.5.1-all.jar

:end
endlocal