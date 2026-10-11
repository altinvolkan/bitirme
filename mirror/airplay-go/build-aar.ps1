# build-aar.sh'nin PowerShell karsiligi (Windows).
# Onkosul: setup.ps1 calismis; ANDROID_HOME ve ANDROID_NDK_HOME ayarli.
#   powershell -ExecutionPolicy Bypass -File .\build-aar.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Run($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$exe $args basarisiz (kod $LASTEXITCODE)" }
}

if (-not $env:ANDROID_NDK_HOME) { throw 'ANDROID_NDK_HOME ayarli degil (ornek: $env:ANDROID_HOME\ndk\<surum>)' }

$env:PATH += ";$(go env GOPATH)\bin"
Run go install golang.org/x/mobile/cmd/gomobile@latest
Run gomobile init
Run go get golang.org/x/mobile/bind

New-Item -ItemType Directory -Force ..\android\app\libs | Out-Null
$targets = if ($env:TARGETS) { $env:TARGETS } else { 'android/arm64' }
# 16 KB sayfa hizasi (Play gereksinimi); NDK r28+ zaten varsayilan, ldflags eski NDK icin
Run gomobile bind "-target=$targets" -androidapi 30 "-ldflags=-extldflags=-Wl,-z,max-page-size=16384" -o ..\android\app\libs\airplay.aar screenmirror/airplay/sender

Write-Host "Uretildi: mirror\android\app\libs\airplay.aar (Android Studio'da Gradle sync yapin)"
