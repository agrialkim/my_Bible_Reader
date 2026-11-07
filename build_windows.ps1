$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$bibleFileName = ([char]0xAC1C).ToString() + [char]0xC5ED + [char]0xAC1C + [char]0xC815 + ".txt"
$biblePath = Join-Path $PSScriptRoot $bibleFileName
$mpvPath = "C:\Program Files\MPV Player\mpv.exe"
$mpvD3DCompilerPath = "C:\Program Files\MPV Player\d3dcompiler_43.dll"
if (-not (Test-Path -LiteralPath $mpvPath)) {
    throw "MPV를 찾을 수 없습니다: $mpvPath"
}
if (-not (Test-Path -LiteralPath $biblePath)) {
    throw "Bible text file was not found: $biblePath"
}

uv run --with-requirements requirements-build.txt python -m PyInstaller `
    --clean `
    --noconfirm `
    --onefile `
    --windowed `
    --name "BibleReader V2" `
    --collect-all edge_tts `
    BibleReaderTTS.py

$distributionDir = Join-Path $PSScriptRoot "Bible Reader Ver 2"
New-Item -ItemType Directory -Path $distributionDir -Force | Out-Null
Copy-Item -LiteralPath ".\dist\BibleReader V2.exe" -Destination $distributionDir -Force
Copy-Item -LiteralPath $biblePath -Destination $distributionDir -Force
Copy-Item -LiteralPath $mpvPath -Destination $distributionDir -Force
if (Test-Path -LiteralPath $mpvD3DCompilerPath) {
    Copy-Item -LiteralPath $mpvD3DCompilerPath -Destination $distributionDir -Force
}

Write-Host "배포 폴더 빌드 완료: $distributionDir"