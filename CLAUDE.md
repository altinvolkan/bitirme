# iMirror (mirror/) - proje notları (yerel oturum için devir)

Android telefondan Apple TV'ye ekran yansıtma. Kullanıcı Türkçe konuşur, kısa ve doğrudan cevap ister. Kod yorumları Türkçe.

## Yapı
- `mirror/android` - Android uygulaması (Kotlin, programatik View, XML/Compose yok). Paket `com.example.screenmirror` (namespace), applicationId `com.altinvolkan.imirror`.
  - `ui/` ekranlar (Home, Pairing, Connecting, Live, Settings, Premium, NoDevice, Onboarding), `Illustrations.kt`, `Ui.kt` (renk/yardımcılar).
  - `MirrorService` (ön plan servisi), `ScreenEncoder` (MediaProjection -> GL `WatermarkRenderer` -> MediaCodec), `AudioCapturer`, `monetization/` (Billing, AdMob, Entitlements).
  - `src/airplay` + `app/libs/airplay.aar` yalnızca AAR varsa derlenir (Go/gomobile AirPlay göndericisi, FairPlay/PIN eşleşme).
  - Metinler `res/values*/strings.xml` (8 dil: en, tr, de, es, fr, it, pt, ru); üretici betik repoda yok, elle düzenle veya yeniden yaz.
- `mirror/airplay-go` - Go kodu; `_overlay/` bizim eklemelerimiz, `setup.ps1` + `build-aar.ps1` (Windows) upstream doubletake'i çekip AAR üretir. Üretilen `internal/ sender/ cmd/ go.mod` git'te yok.
- `mirror/android/tvreceiver` - Android TV alıcısı (ayrı Play uygulaması `com.altinvolkan.imirror.tv`; UDP H.264 + PCM ses; denenmedi, bkz. `mirror/docs/ANDROID_TV.md`).
- `mirror/tvos` - kendi UDP alıcımız (denenmedi). `mirror/docs` - roadmap, gizlilik, RELEASE.md, STORE_LISTING.md. `mirror/store-assets` - simge/grafik (`make_assets.py`).

## Durum (bu noktaya kadar)
- Çalışan: AirPlay eşleşme + yansıtma + ses, döndürme, yeni arayüz, çoklu dil, filigran (üst/alt siyah bant; 10 dk'da çapraz %10, 30 dk %30), 30/60 fps (60 yalnızca Premium), ödüllü reklam, Canlı ekranda banner. Tam ekran reklam kapalı.
- **Son yapılanlar derlenmedi/denenmedi** (bulutta SDK yoktu): Android TV alıcısı modülü + UDP ses + TV bantları, GL filigran/bantlar/fps sınırlama, ödüllü reklam, API 36 (AGP 8.9.1, Gradle 8.11.1) yükseltmesi, imzalama araçları. Derleme hatası çıkarsa önce onları düzelt.
- Kullanıcı tüm değişiklikleri Windows'ta `C:\Users\altin\bitirme` içinde çekip Android Studio ile derliyor. Dal: `claude/gifted-hopper-yw2fwl`.

## Yapılacaklar
1. Derle, hataları düzelt; telefonda dene: filigran bantları, kare hızı (Canlı ekranda ölçülen değer), ısı (telefon aşırı ısınıyordu; GL katmanı etkisi ölçülmedi).
2. İmzalama + AAB: `mirror/docs/RELEASE.md` (anahtar `tools/new-upload-key.ps1`, 16 KB kontrolü `tools/check-16kb.ps1`).
3. Play: kapalı test 12 kişi/14 gün, FGS (mediaProjection) bildirimi + demo video, abonelik `premium_monthly` 99 TRY, gizlilik URL'si (`MainActivity.PRIVACY_URL` yer tutucu), AdMob gerçek kimlikler + app-ads.txt.
4. Açık risk: AirPlay protokolü tersine mühendislikle (doubletake, LGPL) - yasal değerlendirme kullanıcıda. Apple/Android logosu simgede/ekran görüntüsünde kullanma. Uygulama simgesi: telefon + büyük ekran, ikisinde de kırmızı (ısırılmamış) elma - kullanıcının kararı, Apple marka riski var (Apple logosu değil, meyve çizimi); Play şikayet ederse `ic_launcher_foreground.xml` değişir.

5. Android TV'ye alıcı uygulamasız yansıtma = Cast Streaming (kullanıcı C seçeneğini istedi): `mirror/docs/CAST_MIRRORING_PLAN.md`. Yerelde, Open Screen kaynağı okunarak ve gerçek TV ile yapılmalı; `tvreceiver` modülü geri dönüş.

## Kurallar
- Kullanıcı Windows PowerShell kullanıyor; betikler ASCII (Türkçe karakter yok, PS5 bozuyor).
- Başlatma öncesi tam ekran reklam istenmiyor; filigran/çapraz yazı kararı kullanıcının (%30 kalacak).
- Yeni dil/metin ekleyince 8 dilin hepsine ekle.
