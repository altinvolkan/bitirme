# setup.sh'nin PowerShell karşılığı (Windows). Çalıştırma:
#   powershell -ExecutionPolicy Bypass -File .\setup.ps1
$ErrorActionPreference = 'Stop'

$upstream = 'https://github.com/omarroth/doubletake.git'
$commit   = '5e2f1cbe3e1070bcefec8e549c7baf884727bc00'   # 2026-10-04, incelenen sürüm
$here     = $PSScriptRoot
$tmp      = Join-Path $env:TEMP ("dt-" + [guid]::NewGuid())

function Run($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe $args başarısız (kod $LASTEXITCODE)" }
}

try {
    Run git -c advice.detachedHead=false clone -q $upstream "$tmp\up"
    Run git -C "$tmp\up" -c advice.detachedHead=false checkout -q $commit

    New-Item -ItemType Directory -Force "$here\internal\airplay" | Out-Null
    Get-ChildItem "$tmp\up\internal\airplay\*.go" |
        Where-Object { $_.Name -notlike '*_test.go' } |
        Copy-Item -Destination "$here\internal\airplay"
    Copy-Item "$tmp\up\LICENSE", "$tmp\up\COPYING.GPL", "$tmp\up\go.sum" -Destination $here
    (Get-Content "$tmp\up\go.mod") -replace '^module .*', 'module screenmirror/airplay' |
        Set-Content "$here\go.mod" -Encoding ascii

    # Kendi dosyalarımız
    Copy-Item "$here\overlay\*" -Destination $here -Recurse -Force

    Set-Location $here
    Run go mod tidy
    Run go vet ./...
    Run go build ./...
    Write-Host "Hazir. Deneme icin README.md, Android icin .\build-aar.ps1"
}
finally {
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}
