# Play Store yol haritası (reklam + ayda 99 TL reklamsız abonelik)

Durum: faturalandırma, reklam ve onay (UMP) kodu yazıldı, **derlenip denenmedi**. Aşağıdaki
her madde, Play'e göndermeden önce yapılması gerekenleri sırasıyla listeler. Politika ve
kota bilgileri yazım tarihindeki bilgiyle verilmiştir; her adımda Play Console'daki güncel
gereksinimi kontrol edin.

## Faz 0 – Git / gitme kararı (hukuki). Önce bunu çözün.

Para kazanmak, projenin en riskli parçasını görünür kılar. Karar vermeden kod/mağaza işine
girmeyin.

| Konu | Risk | Not |
|---|---|---|
| FairPlay taklidi | Apple'ın kopya koruma sistemini ticari ürün içinde aşmak; ABD DMCA §1201 / AB ve Türkiye telif hukuku kapsamında iddia konusu olabilir | Kişisel kullanım ile satış farklı risk seviyesidir. Hukuki görüş alın |
| Marka | "AirPlay", "Apple TV", "FairPlay" Apple markasıdır | Uygulama adında, ikonunda, mağaza başlığında kullanmayın; açıklamada yalnızca "uyumlu" demek için, Apple yönergelerine uyarak anın |
| Play politikaları | Fikri mülkiyet ve taklit (impersonation) politikaları; Apple şikâyet ederse uygulama kaldırılabilir, hesap uyarı alır | Hesabınızın kapanma riski, diğer tüm uygulamalarınızı da etkiler |
| Lisans | doubletake LGPL-3.0: kütüphaneyi kapalı kaynaklı uygulamada kullanmak için kullanıcıya yeniden bağlama (relink) imkânı ve kaynak sunmak gerekir | gomobile AAR tek bir `.so` içine statik bağlar; uyum için ek çalışma ve hukuki görüş gerekir |
| Apple tarafı | Apple TV alıcısı olarak kendi tvOS uygulamamız temiz bir yol | Bu yol için Apple Developer hesabı (yılda 99 USD) ve App Store incelemesi gerekir |

**Önerilen yol:** Ücretli/reklamlı sürümde sadece sahibi olduğunuz parçaları satın:
Android uygulaması + kendi tvOS alıcınız (UDP protokolü, `mirror/tvos`). AirPlay (doğrudan
Apple TV) yolunu mağaza sürümüne koymayın veya ayrı, ücretsiz, kaynak açık bir yapı olarak
tutun. AirPlay'i mağazaya koymak istiyorsanız yalnızca hukuki görüş sonrası ve bilerek.
Kod, AirPlay bölümünü zaten `airplay.aar` yoksa derlemeden çıkarıyor; mağaza sürümünü AAR'sız
derlemek yeterli.

## Faz 1 – Teknik hazırlık

1. **Paket adı:** `applicationId`'yi benzersiz yapın (örn. `com.sirketiniz.ekranyansit`).
   Yayından sonra değişmez. `namespace` kod paketi olarak kalabilir.
2. **Hedef SDK:** Play, yeni uygulamalar için güncel bir `targetSdk` ister (şu an 34; 35 veya
   36 gerekir, Console'daki uyarıya bakın). Android Studio → Tools → AGP Upgrade Assistant ile
   `compileSdk`/`targetSdk`/AGP/Gradle'ı birlikte yükseltin; ardından Android 15/16 değişikliklerini
   test edin (edge-to-edge zorunluluğu, ön plan servisi kısıtları, MediaProjection davranışları).
3. **AAB:** Play APK değil App Bundle ister: `gradle bundleRelease`
   (`app/build/outputs/bundle/release/`).
4. **İmza:** Play App Signing'i açın; kendi yükleme anahtarınızı üretin
   (`keytool -genkey -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`).
   `mirror/android/keystore.properties` dosyasına `storeFile`, `storePassword`, `keyAlias`,
   `keyPassword` yazın (git'e girmez). Anahtarı güvenli yedekleyin.
5. **R8/ProGuard:** release derlemede küçültme açık (`proguard-rules.pro`). Release AAB'yi gerçek
   cihazda baştan sona deneyin (yansıtma, abonelik, reklam).
6. **Kararlılık:** bağlantı kopunca yeniden bağlanma, hata mesajları, düşük pil davranışı, farklı
   Android sürümleri/üreticiler (özellikle MediaProjection ve ön plan servisi). Crash raporlama
   (Firebase Crashlytics veya Play Console'un Android vitals'ı).
7. **Ön plan servisi bildirimi:** `mediaProjection` ön plan servisi tipi için Play Console'da
   "Ön plan servisi izinleri" formu ve genellikle kısa bir tanıtım videosu istenir.
8. **Kullanıcı arayüzü:** ikon, uygulama adı (marka içermeyen), tema, tüm metinler için
   İngilizce + Türkçe `strings.xml` (şu an metinler kodda).

## Faz 2 – Gelir entegrasyonu (kod hazır, hesapları kurun)

**Play Console – abonelik**
1. Monetize → Products → Subscriptions → Ürün kimliği **`premium_monthly`** (kodla aynı olmalı).
2. Tek temel plan: aylık, otomatik yenilenen, fiyat **99 TRY** (vergi dahil fiyatı Console
   gösterir). Diğer ülkeler için fiyatı Console'un önerisiyle bırakın veya kendiniz verin.
3. İsteğe bağlı: ücretsiz deneme veya tanıtım teklifi. Kod ilk teklifi (`offerToken`) kullanır;
   birden fazla teklif eklerseniz `BillingManager.launchPurchase`'ı buna göre seçin.
4. Test: Setup → License testing'e Gmail hesaplarınızı ekleyin; iç test kanalına yükleyip satın
   alma, iptal, yenileme, "geri yükle" akışlarını deneyin.
5. Komisyon: abonelikte Play hizmet bedeli çoğunlukla %15 (güncel oran için Console'a bakın).
   Net gelir 99 TL'den düşük olur.

**AdMob**
1. admob.google.com'da hesap + uygulama oluşturun; **Banner** ve **Geçiş (interstitial)** reklam
   birimi açın.
2. Kimlikleri `~/.gradle/gradle.properties` içine yazın (repoya girmez):
   ```
   ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
   ADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
   ADMOB_INTERSTITIAL_ID=ca-app-pub-XXXXXXXXXXXXXXXX/WWWWWWWWWW
   ```
   Verilmezse kod Google'ın test kimliklerini kullanır. Gerçek kimlikle **kendi cihazınızda test
   reklamına tıklamayın** (hesap askıya alınabilir); test cihazı kaydı kullanın.
3. AdMob → Privacy & messaging: AEA/GDPR onay mesajını oluşturup yayınlayın (UMP kodu hazır),
   gerekirse ABD eyalet mesajını da.
4. Reklam yerleşimi: ana ekranda banner; yansıtmayı başlatmadan önce en fazla 3 dakikada bir
   geçiş reklamı. **Yansıtma sırasında reklam gösterilmez.** Premium kullanıcıya reklam yüklenmez.
5. Ödeme (AdMob): vergi ve ödeme bilgilerini tamamlayın.

**Bilinen sınırlar (v1):** abonelik doğrulaması cihaz üzerinde yapılıyor. Kötüye kullanıma karşı
sonraki adım: Real-time developer notifications + sunucuda Play Developer API doğrulaması.

## Faz 3 – Mağaza sayfası ve politika formları

- Geliştirici hesabı: tek seferlik 25 USD. Kimlik doğrulama gerekir. Kişisel hesaplarda yeni
  uygulamayı yayına almadan önce **kapalı testte en az 12 test kullanıcısı, 14 gün** koşulu olabilir;
  güncel şartı Console'dan doğrulayın. Kurumsal hesap için D-U-N-S numarası gerekir.
- Gizlilik politikası: herkese açık bir URL'de yayınlayın (taslak: `PRIVACY_POLICY.md`).
- **Veri güvenliği formu:** reklam kimliği (AdMob), satın alma bilgisi (Play), tanılama/çökme
  verisi (kullanıyorsanız). Kamera/konum/rehber yok. Ses: sistem sesi cihaz dışına yalnızca
  kullanıcının seçtiği TV'ye gönderilir, kaydedilmez; formda bunu doğru belirtin.
- Reklam içerdiğini işaretleyin; içerik derecelendirme anketini doldurun; hedef kitle: 13+ (çocuklara
  yönelik değil).
- Mağaza metni: kısa/uzun açıklama (TR + EN), 2–8 ekran görüntüsü (telefon), 512×512 ikon,
  1024×500 öne çıkan görsel. Marka ihlali içeren ifade ve görsel kullanmayın.
- Kategori: Araçlar veya Video oynatıcılar.
- Hassas izinler: `RECORD_AUDIO` ve ekran yakalama için uygulama içi açıklama gösterin (kod
  izni kullanıcı bir eylem yapınca istiyor) ve mağaza metninde neden gerektiğini yazın.

## Faz 4 – Sürüm

1. İç test (anında, 100 kişiye kadar) → abonelik/reklam doğrulaması.
2. Kapalı test (gerekirse 12 kişi × 14 gün).
3. Üretim: önce %10–20 kademeli dağıtım; Android vitals (çökme, ANR) ve abonelik metriklerini izleyin.

## Faz 5 – Yayın sonrası

- Sunucu tarafı abonelik doğrulaması, iade/iptal işleme, destek e-postası.
- Düzenli güncelleme: yeni Android sürümleri, Apple TV/tvOS sürümleriyle uyum (AirPlay yolunu
  kullanıyorsanız her tvOS güncellemesi kırabilir).
- Ölçüm: yansıtma başarı oranı, ortalama oturum süresi, dönüşüm (ücretsiz → premium).

## Tahmini sıra ve süre (tek geliştirici)

| Faz | Süre |
|---|---|
| 0 Hukuki karar | 1–3 hafta (danışmanlığa bağlı) |
| 1 Teknik hazırlık | 1–2 hafta |
| 2 Gelir entegrasyonu hesapları + test | 3–5 gün |
| 3 Mağaza sayfası, formlar | 3–5 gün |
| 4 Test süreleri | 2–3 hafta (kapalı test koşulu varsa) |

## Maliyet özeti
- Play Console: 25 USD (tek sefer)
- Apple Developer (tvOS alıcı için): 99 USD / yıl
- Hukuki görüş: değişken
- AdMob: ücretsiz; gelir paylaşımı Google'da
