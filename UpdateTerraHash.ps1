param(
    [Parameter(Mandatory)][string]$Hash,
    [Parameter(Mandatory)][string]$GradleFile
)
$content = Get-Content $GradleFile -Raw
$updated = $content -replace 'val terraGitHash = "[^"]*"', "val terraGitHash = `"$Hash`""
[System.IO.File]::WriteAllText($GradleFile, $updated, [System.Text.Encoding]::UTF8)
Write-Host "terraGitHash updated to $Hash in $GradleFile"
