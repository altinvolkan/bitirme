# Android → Apple TV düşük gecikmeli ekran yansıtma

İki yol var: (A) Apple TV'ye doğrudan AirPlay ile, uygulama kurmadan: `airplay-go/README.md`.
(B) Kendi tvOS alıcımız + UDP: aşağıdaki anlatım.

```
Android (mirror/android)                              Apple TV (mirror/tvos)
MediaProjection → MediaCodec H.264 → UDP  ───────►  UDP → birleştirme → AVSampleBufferDisplayLayer
                  ◄─── keyframe isteği (UDP) ───────
         Bonjour: _scrmirror._udp (port 52000)
```

## Derleme

**Android:** `mirror/android` klasörünü Android Studio ile aç → Run. (minSdk 30)
Gradle wrapper betikleri repoda yok; Android Studio `gradle-wrapper.properties`'ten kendisi indirir.

**tvOS (Mac gerekli):**
```
brew install xcodegen
cd mirror/tvos && xcodegen        # ScreenMirrorTV.xcodeproj üretir
open ScreenMirrorTV.xcodeproj     # Signing & Capabilities'ten Team seç, Apple TV'ye Run
```
Xcode kullanmadan elle açacaksan: yeni tvOS App projesi oluştur, `Sources/*.swift` dosyalarını ekle ve Info.plist'e
`NSLocalNetworkUsageDescription` + `NSBonjourServices = [_scrmirror._udp]` ekle.

## Kullanım
1. Apple TV'de uygulamayı aç (ekranda IP:port yazar).
2. Telefon ve Apple TV **aynı 5 GHz Wi-Fi**'da olsun.
3. Android uygulamasında Apple TV otomatik bulunur (bulunamazsa IP'yi elle yaz) → "Yansıtmayı başlat".

## Gecikme için yapılanlar
- Ekran → encoder input surface (CPU kopyası yok), H.264 Baseline, B-frame yok, CBR, `low-latency`, realtime priority.
- Çözünürlük uzun kenar 1280'e indirilir; 6 Mbps, 60 fps (`ScreenEncoder` / `MirrorService.MAX_SIDE`).
- Aktarım UDP; kayıp karede beklenmez. Alıcı boşluk görünce P-kareleri atar ve keyframe ister (en fazla 200 ms'de bir).
- Alıcıda jitter buffer yok, kareler `DisplayImmediately` ile çizilir.
- Durağan ekranda 500 ms'de bir kare tekrarlanır, 2 sn'de bir keyframe gelir.

## Bilinen sınırlar / sonraki adımlar
- **Bu kod derlenip cihazda denenmedi** (geliştirme ortamında Android SDK / Xcode yok). İlk denemede küçük derleme düzeltmeleri çıkabilir.
- Ses yok. Ekran döndürmede encoder yeniden kurulmuyor (servisi yeniden başlat).
- Paket kaybı için FEC / bitrate adaptasyonu yok; kötü Wi-Fi'da görüntü bozulup keyframe ile toparlanır.
- Şifreleme / eşleştirme yok: aynı ağdaki herkes porta yayın yapabilir. Kalıcı kullanım için PIN eşleştirme ekle.
- Keyframe'ler tek seferde gönderilir (pacing yok); gerekirse paketler arası mikro-gecikme ekle.
- Gecikmeyi ölçmek için: Android'de ekranda milisaniye sayacı göster, TV'yi telefonla aynı karede filme al.

## Arayüz (v0.2)

Ekranlar `android/app/src/main/java/com/example/screenmirror/ui/` altında programatik View olarak yazıldı
(Ana, Eşleştirme, Bağlanıyor, Canlı, Ayarlar, Premium, Ekran bulunamadı, ilk açılış ipucu turu).

- Yazı tipleri isteğe bağlı: `app/src/main/assets/fonts/Figtree.ttf` ve `BricolageGrotesque.ttf`
  (değişken yazı tipi) koyulursa kullanılır, yoksa sistem yazı tipi.
- Gizlilik politikası bağlantısı `MainActivity.PRIVACY_URL` içinde; yayından önce gerçek adresle değiştir.
- IP ile bağlan: AirPlay kütüphanesi varsa 7000 portlu AirPlay cihazı, yoksa UDP alıcısı eklenir.

## Marka: iMirror

- Uygulama kimliği (applicationId): `com.altinvolkan.imirror` (kod paketi `com.example.screenmirror` olarak kaldı; Play'e yüklenen yalnızca applicationId'dir).
- Simge: ekran çerçevesi + yukarı bakan üçgen (yayın simgesi); `android/app/src/main/res/drawable/ic_launcher_*.xml` (uyarlanabilir + temalı/monokrom).
- Mağaza görselleri: `store-assets/` (`python make_assets.py` ile yeniden üretilir): 512×512 simge ve 1024×500 öne çıkan grafik.
