# iMirror: imzalama ve AAB (Play yayını) adımları

Önkoşullar: Android Studio **2024.3.1 (Meerkat) veya daha yeni** (hedef API 36 için AGP 8.9.1 + Gradle 8.11.1 gerekir; repo bunlara ayarlı). Eski Studio "bu AGP'yi desteklemiyorum" derse Studio'yu güncelle.

## 0) Güncel kodu çek (Windows PowerShell)

`mirror/android/build.gradle.kts` ve `gradle/wrapper/gradle-wrapper.properties` dosyalarında yerelde değişiklik yaptıysan `git pull` çakışır. Repodaki sürümler (AGP 8.9.1, Gradle 8.11.1) bunların yerini alıyor:

```powershell
cd C:\Users\altin\bitirme
git checkout -- mirror/android/build.gradle.kts mirror/android/gradle/wrapper/gradle-wrapper.properties
git pull origin claude/gifted-hopper-yw2fwl
```

## 1) AirPlay kütüphanesini 16 KB hizalı üret

Play, Android 15+ hedefleyen uygulamalarda 64-bit yerel kitaplıkların **16 KB sayfa boyutuna** hizalı olmasını ister. NDK r28 veya yeni olanı kullan (SDK Manager → SDK Tools → NDK). Betikler ayrıca linker bayrağını ekler.

```powershell
cd C:\Users\altin\bitirme\mirror\airplay-go
.\setup.ps1
.\build-aar.ps1
cd ..\android
powershell -ExecutionPolicy Bypass -File .\tools\check-16kb.ps1 -Archive app\libs\airplay.aar
```

Çıktıda her `.so` için `OK ... hiza 0x4000` (veya daha büyük) görmelisin. `HATA` görürsen NDK'yı r28+ yap ve `build-aar.ps1`'i yeniden çalıştır.

## 2) Yükleme anahtarını oluştur (bir kez)

```powershell
cd C:\Users\altin\bitirme\mirror\android
powershell -ExecutionPolicy Bypass -File .\tools\new-upload-key.ps1
```

`upload-keystore.jks` ve `keystore.properties` oluşur (ikisi de `.gitignore`'da). **Hemen yedekle** (parola yöneticisi + harici disk). Kaybedersen Play Console'dan "yükleme anahtarını sıfırla" talebi gerekir.

Play App Signing: yeni uygulamalarda varsayılan. Uygulamayı sen bu **yükleme anahtarıyla** imzalayıp yüklersin, kullanıcılara giden asıl imzayı Google tutar (anahtar çalınsa/kaybolsa bile kurtarılabilir).

## 3) Gerçek AdMob kimlikleri

`%USERPROFILE%\.gradle\gradle.properties` içine (repoya değil) yaz:

```properties
ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
ADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
ADMOB_REWARDED_ID=ca-app-pub-XXXXXXXXXXXXXXXX/WWWWWWWWWW
ADMOB_INTERSTITIAL_ID=ca-app-pub-XXXXXXXXXXXXXXXX/VVVVVVVVVV
```

İlk iç test yüklemesi için test kimlikleriyle devam edebilirsin (derleme uyarı verir, hata vermez). Gerçek kimliklerle test ederken **kendi reklamına tıklama**; AdMob hesabı kapanabilir.

## 4) AAB'yi üret

Android Studio: **Build → Generate Signed App Bundle / APK → Android App Bundle**, `upload-keystore.jks` + parola, `release` seç. Ya da (Gradle wrapper betiği varsa):

```powershell
cd C:\Users\altin\bitirme\mirror\android
.\gradlew.bat bundleRelease
```

Çıktı: `app\build\outputs\bundle\release\app-release.aab`. Derleme `keystore.properties` yoksa bilerek durur.

Gönderme öncesi doğrulamalar:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\check-16kb.ps1 -Archive app\build\outputs\bundle\release\app-release.aab
```

**Yayın (R8 küçültmeli) derlemeyi telefonda mutlaka dene** (R8 yansımayla yüklenen sınıfları silebilir): `.\gradlew.bat assembleRelease` ile APK üretip yükle; ana ekran, AirPlay eşleşme, yansıtma, Premium ekranı çalışmalı.

## 5) Play Console

1. Uygulamayı oluştur (ad: iMirror, varsayılan dil, ücretsiz).
2. **Test ve yayın → Dahili test** → yeni sürüm oluştur → AAB'yi yükle. Play App Signing'i kabul et.
3. Test kullanıcıları ekle (e-posta listesi). Üretime çıkmadan önce kapalı testte **12 kullanıcı / 14 gün** şartı için bkz. `PLAY_STORE_ROADMAP.md`.
4. **Uygulama içeriği** formları: gizlilik politikası URL'si, Veri güvenliği, reklam beyanı, içerik derecelendirmesi, hedef kitle, **ön plan hizmeti (mediaProjection)** bildirimi + demo video (izin penceresinden yayına kadar).
5. **Gelir elde etme → Abonelikler**: ürün kimliği `premium_monthly`, aylık temel plan, fiyat 99 TRY. **Ayarlar → Lisans testi**'ne test hesabını ekle (gerçek para çekilmeden satın alma denenir).
6. Mağaza girişi: metinler `STORE_LISTING.md`, simge ve öne çıkan grafik `mirror/store-assets/`. Ekran görüntüleri: Apple TV arayüzü/logosu görünmesin.

## 6) Her yeni sürümde

`app/build.gradle.kts` içinde `versionCode`'u artır (örn. 1 → 2), `versionName`'i güncelle, AAB'yi yeniden üret ve yükle.
