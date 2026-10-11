# AirPlay göndericisi (Android → Apple TV, doğrudan)

Apple TV'nin kendi AirPlay alıcısına, tvOS'a uygulama kurmadan yansıtır.
Protokol katmanı (FairPlay SAP, SRP-6a PIN eşleştirme, RTSP, ChaCha20 akış şifrelemesi)
[omarroth/doubletake](https://github.com/omarroth/doubletake) projesinden gelir (LGPL-3.0).
Bu klasördeki `_overlay/` bizim yazdığımız kısımdır:

| Dosya | Görevi |
|---|---|
| `_overlay/internal/airplay/external.go` | MediaCodec çıktısını doubletake'in `StreamFrames`'ine besleyen kaynak (GStreamer yerine) |
| `_overlay/internal/airplay/device_txt.go` | NsdManager TXT kayıtlarından `AirPlayDevice` kurar |
| `_overlay/sender/sender.go` | gomobile arayüzü: bağlan, eşleş (PIN), yayını kur, kare it |
| `_overlay/cmd/h264send` | Android'e geçmeden Linux'ta kendi Apple TV'nize deneme aracı |

Upstream kodu repoya işlenmez; `setup.sh` sabit bir commit'ten çekip birleştirir.

## Kurulum
```
cd mirror/airplay-go
./setup.sh          # upstream'i çeker, _overlay dosyalarini uygular, go vet + go build
```

## 1) Önce Linux'ta dene (Android olmadan)
```
ffmpeg -nostats -loglevel error -re -f lavfi -i testsrc2=size=1280x720:rate=30 \
  -c:v libx264 -profile:v baseline -preset ultrafast -tune zerolatency -g 30 \
  -x264-params repeat-headers=1:aud=1 -f h264 - \
 | go run ./cmd/h264send -ip <APPLE_TV_IP>
```
İlk bağlantıda Apple TV bir kod gösterir. `h264send` kodu sorar ve `creds-*.json` kaydeder
(sonra `-creds` ile verilir). Test deseni TV'de görünüyorsa FairPlay ve eşleştirme sizin
tvOS sürümünüzde çalışıyor demektir.

## 2) Android
```
export ANDROID_HOME=...   # SDK + NDK kurulu olmalı
./build-aar.sh            # ../android/app/libs/airplay.aar üretir
```
Android Studio'da `mirror/android`'i açıp Gradle sync yapın. `airplay.aar` varsa AirPlay
bölümü arayüzde görünür; yoksa uygulama sadece UDP moduyla derlenir.

## Notlar
- Ses: Android'in AudioPlaybackCapture'ı ile sistem sesi yakalanır, ALAC olarak gönderilir
  (RECORD_AUDIO izni gerekir). Ses yakalamayı yasaklayan uygulamalar sessiz kalır; telefonun
  hoparlörü de çalmaya devam eder. Görüntü ile ses arası kayma varsa `Session.setVideoLatencyMs`
  (varsayılan 30) ayarlanabilir.
- Ekran döndürülünce encoder yeni boyutla yeniden kurulur (dikey ↔ yatay).
- Kod bu geliştirme ortamında derlenip denenmedi; ilk derlemede düzeltme gerekebilir.
- Apple TV'de Ayarlar → AirPlay ve HomeKit → "Erişime İzin Ver" değeri "Aynı Ağdaki Herkes" olmalı.
- LGPL-3.0: upstream dosyalarında yaptığınız değişiklikleri dağıtırsanız paylaşmanız gerekir.

## Windows
Betiklerin PowerShell sürümleri var (`setup.ps1`, `build-aar.ps1`):
```
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```
Test komutunu **cmd.exe**'de çalıştırın; PowerShell'in boru hattı ikili veriyi bozar:
```
cmd /c "ffmpeg -nostats -loglevel error -re -f lavfi -i testsrc2=size=1280x720:rate=30 -c:v libx264 -profile:v baseline -preset ultrafast -tune zerolatency -g 30 -x264-params repeat-headers=1:aud=1 -f h264 - | go run ./cmd/h264send -ip <APPLE_TV_IP>"
```
`ANDROID_NDK_HOME` ayarlayın, ardından `.\build-aar.ps1`.
