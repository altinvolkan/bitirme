# .aar / .aab / .apk icindeki 64-bit .so dosyalarinin 16 KB hizali olup olmadigini kontrol eder.
#   powershell -ExecutionPolicy Bypass -File .\tools\check-16kb.ps1 -Archive app\libs\airplay.aar
#   powershell -ExecutionPolicy Bypass -File .\tools\check-16kb.ps1 -Archive app\build\outputs\bundle\release\app-release.aab
# Onkosul: ANDROID_NDK_HOME ayarli (llvm-readelf icin).
param([Parameter(Mandatory = $true)][string]$Archive)
$ErrorActionPreference = 'Stop'

if (-not $env:ANDROID_NDK_HOME) { throw 'ANDROID_NDK_HOME ayarli degil.' }
$readelf = Join-Path $env:ANDROID_NDK_HOME 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path $readelf)) { throw "llvm-readelf bulunamadi: $readelf" }
if (-not (Test-Path $Archive)) { throw "Dosya yok: $Archive" }

$tmp = Join-Path $env:TEMP ('so-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
try {
    Copy-Item $Archive (Join-Path $tmp 'a.zip')   # Expand-Archive .zip uzantisi ister
    Expand-Archive -Path (Join-Path $tmp 'a.zip') -DestinationPath (Join-Path $tmp 'x')
    $libs = Get-ChildItem -Recurse -Filter *.so (Join-Path $tmp 'x') |
        Where-Object { $_.FullName -match 'arm64-v8a|x86_64' }
    if (-not $libs) { Write-Host '64-bit .so bulunamadi (native kod yok ya da yalnizca 32-bit).'; return }

    $bad = 0
    foreach ($so in $libs) {
        $loads = & $readelf -lW $so.FullName | Select-String '^\s+LOAD'
        $minAlign = [int64]::MaxValue
        foreach ($l in $loads) {
            $align = ($l.Line.Trim() -split '\s+')[-1]
            $v = [Convert]::ToInt64($align.Replace('0x', ''), 16)
            if ($v -lt $minAlign) { $minAlign = $v }
        }
        if ($minAlign -ge 16384) {
            Write-Host ("OK    {0}  (hiza 0x{1:x})" -f $so.Name, $minAlign) -ForegroundColor Green
        } else {
            Write-Host ("HATA  {0}  (hiza 0x{1:x}, en az 0x4000 olmali)" -f $so.Name, $minAlign) -ForegroundColor Red
            $bad++
        }
    }
    if ($bad -gt 0) { throw "$bad dosya 16 KB hizali degil." }
    Write-Host 'Tum 64-bit .so dosyalari 16 KB hizali.' -ForegroundColor Green
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
