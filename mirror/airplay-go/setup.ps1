# setup.sh'nin PowerShell karsiligi (Windows). Calistirma:
#   powershell -ExecutionPolicy Bypass -File .\setup.ps1
$ErrorActionPreference = 'Stop'

$upstream = 'https://github.com/omarroth/doubletake.git'
$commit   = '5e2f1cbe3e1070bcefec8e549c7baf884727bc00'   # 2026-10-04, incelenen surum
$here     = $PSScriptRoot
$tmp      = Join-Path $env:TEMP ("dt-" + [guid]::NewGuid())

function Run($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe $args basarisiz (kod $LASTEXITCODE)" }
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

    # Kendi dosyalarimiz
    Copy-Item "$here\_overlay\*" -Destination $here -Recurse -Force

    # Windows: Pdeathsig sadece Linux'ta var (GStreamer sureci icin, bizde kullanilmiyor)
    $cap = "$here\internal\airplay\capture.go"
    $src = Get-Content $cap -Raw -Encoding UTF8
    $src = $src.Replace('&syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}', '&syscall.SysProcAttr{}')
    Set-Content $cap $src -Encoding utf8

    Set-Location $here
    Run go mod tidy
    Run go build ./...
    Run go vet ./sender/... ./cmd/...

    # Android icin derlenebilirlik on kontrolu (hata olursa sadece uyarir)
    $env:GOOS = 'android'; $env:GOARCH = 'arm64'; $env:CGO_ENABLED = '0'
    & go build ./internal/... ./sender/...
    $androidOk = ($LASTEXITCODE -eq 0)
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED
    if (-not $androidOk) { Write-Warning 'GOOS=android on kontrolu basarisiz; yukaridaki hatayi bana gonderin.' }

    Write-Host "Hazir. Deneme icin README.md, Android icin .\build-aar.ps1"
}
finally {
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}
