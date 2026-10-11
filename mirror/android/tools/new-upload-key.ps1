# Play yukleme anahtari (upload key) uretir ve keystore.properties yazar.
# Calistir (mirror\android klasorunde):
#   powershell -ExecutionPolicy Bypass -File .\tools\new-upload-key.ps1
# Cikti: upload-keystore.jks + keystore.properties (ikisi de .gitignore'da; ASLA repoya koyma).
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

$ksFile = 'upload-keystore.jks'
$alias = 'upload'
if (Test-Path $ksFile) { throw "$ksFile zaten var. Uzerine yazmamak icin durdum (silmeden once yedegini al)." }

# keytool'u bul: JAVA_HOME, Android Studio'nun JBR'si veya PATH
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
$candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
$keytool = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $keytool) {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source }
}
if (-not $keytool) { throw 'keytool bulunamadi. Android Studio kurulu mu? JAVA_HOME ayarla.' }

$name = Read-Host 'Adin Soyadin (sertifika CN)'
$country = Read-Host 'Ulke kodu (ornek TR)'
$p1 = Read-Host 'Anahtar parolasi (en az 8 karakter, KAYBETME)' -AsSecureString
$p2 = Read-Host 'Parolayi tekrar yaz' -AsSecureString
function ToPlain($s) {
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}
$pw = ToPlain $p1
if ($pw -ne (ToPlain $p2)) { throw 'Parolalar uyusmuyor.' }
if ($pw.Length -lt 8) { throw 'Parola en az 8 karakter olmali.' }
$dn = "CN=$name, C=$country"

& $keytool -genkeypair -v -keystore $ksFile -alias $alias -keyalg RSA -keysize 2048 -validity 10000 `
    -storetype PKCS12 -storepass $pw -keypass $pw -dname $dn
if ($LASTEXITCODE -ne 0) { throw 'keytool basarisiz.' }

$props = @(
    "storeFile=$ksFile",
    "storePassword=$pw",
    "keyAlias=$alias",
    "keyPassword=$pw"
)
Set-Content -Path 'keystore.properties' -Value $props -Encoding ASCII

Write-Host ''
Write-Host "Hazir: $ksFile ve keystore.properties" -ForegroundColor Green
Write-Host 'ONEMLI: Bu iki dosyayi ve parolayi simdi yedekle (parola yoneticisi + harici disk).' -ForegroundColor Yellow
Write-Host 'Parolayi kaybedersen Play Console uzerinden anahtar sifirlama istemen gerekir (gunler surebilir).'
& $keytool -list -v -keystore $ksFile -alias $alias -storepass $pw | Select-String 'SHA1|SHA256'
