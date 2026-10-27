# Android TV desteği: iMirror TV alıcısı

Telefondaki iMirror, Apple TV'ye AirPlay ile gönderdiği gibi Android TV'lere de görüntü (ve ses) gönderebilir.
Android TV tarafında küçük bir **alıcı uygulama** çalışır: `mirror/android/tvreceiver` (applicationId
`com.altinvolkan.imirror.tv`, Play'de ayrı, ücretsiz bir TV uygulaması olarak yayınlanır).

## Nasıl çalışır
- TV uygulaması açıkken UDP 52000 portunu dinler ve ağda `_scrmirror._udp` olarak (`platform=androidtv`)
  duyurulur. Telefon uygulaması bunu "Android TV" alt başlığıyla listeler.
- Telefon, ekranı H.264 olarak kodlayıp UDP ile yollar; TV `MediaCodec` ile çözüp yüzeye basar (düşük gecikme).
- Ses: telefonun sistem sesi PCM (44,1 kHz, stereo) olarak aynı yoldan gider; TV `AudioTrack` ile çalar.
- Kayıp olursa TV, telefondan anahtar kare ister (geri kanal). Telefon dönünce çözünürlük değişir, çözücü yeniden kurulur.
- Protokol: `android/app/.../Protocol.kt` (telefon) = `tvreceiver/.../Proto.kt` (TV) = `tvos/Sources/Proto.swift`.
- Ücretsiz sürümde filigran aynı şekilde basılır (görüntü kareye eklenen siyah bantlara yazılır; TV'de 16:9 varsayılır).

## Derleme / yayın
```powershell
cd C:\Users\altin\bitirme\mirror\android
# Android Studio'da Gradle sync; "tvreceiver" modülü listelenir. TV'ye yüklemek için:
#   Run yapılandırmasını "tvreceiver" seç, hedef: Android TV (veya adb connect <tv-ip> ile gerçek TV)
.\gradlew.bat :tvreceiver:bundleRelease     # imzalı AAB (keystore.properties gerekir, bkz. RELEASE.md)
```
Play Console'da **ayrı bir uygulama** oluştur: ad "iMirror TV", paket `com.altinvolkan.imirror.tv`, ücretsiz.
TV uygulaması gereksinimleri: Leanback başlatıcı + banner (hazır: `res/drawable-nodpi/banner.png`),
uzaktan kumandayla kullanım (şu an tek ekran, düğme yok), 1920x1080 TV ekran görüntüleri,
"Form faktörleri" altında Android TV'yi etkinleştir (TV test kanalı ayrıdır).

## Denenmesi gerekenler (henüz denenmedi)
1. TV uygulaması açılır, ağda görünür ve telefonda listede "Android TV" olarak çıkar mı? (Görünmezse TV ekranındaki
   `IP:52000` adresini (iki nokta üst üste ve port dahil) telefonda "IP ile bağlan" alanına aynen gir.)
2. Görüntü açılıyor mu, döndürünce toparlanıyor mu, gecikme nasıl?
3. Ses geliyor mu? Görüntüyle ne kadar kayma var? (Gerekirse ses tamponu `AudioPlayer.kt` içinde ayarlanır.)
4. Hangi TV'lerde donanım çözücü düşük gecikmeyi (`low-latency` anahtarı) destekliyor?

## Alternatif: sistem yansıtması
Birçok Android TV / Chromecast zaten Google Cast veya Miracast ile **telefonun kendi "Ekranı yayınla"**
özelliğini destekler; bu iMirror'dan bağımsız çalışır ve alıcı uygulaması gerektirmez. iMirror'un AirPlay göndericisi
ise AirPlay destekleyen TV'lerde (Samsung, LG, Sony vb. AirPlay 2 alıcıları) listede çıkabilir; onların uyumluluğu test edilmedi.
