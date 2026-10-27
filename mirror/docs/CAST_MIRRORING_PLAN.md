# Cast (Chromecast built-in / Android TV) yansıtma

TV'ye uygulama kurmadan, Android TV / Chromecast / Google TV'nin **yerleşik Cast alıcısına** yansıtır. Chrome'un "Cast ekranı"
yansıtmasının kullandığı **Cast Streaming** protokolünün gönderici tarafını telefonda uygular.

Durum: **kodlandı, JVM'de sahte alıcıyla sınandı, gerçek TV'de DENENMEDİ.**

## Kod (`app/src/main/java/com/example/screenmirror/cast/`)
- `CastTarget.kt` - `_googlecast._tcp` keşfi (TXT: `fn` ad, `md` model, `ca` yetenek; yalnızca video çıkışı olanlar listelenir).
- `CastChannel.kt` - Cast V2 kontrol kanalı: TLS 8009 (sertifika doğrulaması yok), 4 bayt uzunluk + CastMessage protobuf (elle kodlu).
- `CastBackend.kt` - oturum: CONNECT -> LAUNCH `0F5096E8` ("Chrome Mirroring") -> RECEIVER_STATUS (transportId) -> CONNECT -> OFFER
  (`urn:x-cast:com.google.cast.webrtc`) -> ANSWER (udpPort, ssrcs, sendIndexes) -> akış. `MirrorBackend` arayüzünü uygular;
  `MirrorService.startCast()` AirPlay ile aynı yolu (`startBackend`) kullanır: tuval gelince encoder + ses yakalama başlar.
- `CastStream.kt` - bir akış (video/ses): AES-128-CTR, Cast RTP paketleme, SR (500 ms; ilk 100 karede her karenin önünde),
  alıcı RTCP'si (PLI -> anahtar kare isteği, Cast ACK/NACK -> yeniden gönderim).
- `OpusEncoder.kt` - ses: `MediaCodec` `audio/opus` (48 kHz stereo, 96 kbps). Yoksa/açılamazsa OFFER'a ses konmaz (sessiz yansıtma).
- Video: mevcut `ScreenEncoder` çıktısı (H.264 Baseline, Annex-B, anahtar karede SPS/PPS önde) doğrudan paketlenir; GL filigran/bant
  katmanı ve Premium mantığı aynen geçerli. Kare hızı Cast'te 30 fps ile sınırlı (`startCast`).

## Dayanak (Chromium 100.0.4896.60 etiketi, GitHub aynasından okundu)
`media/cast/net/rtp/rtp_packetizer.cc` (RTP 12 bayt + Cast 7 bayt başlık, uzantı: `0x04 0x02 gecikme_ms`), `rtp_defines.h`,
`common/transport_encryption_handler.cc` (AES-CTR sayaç: bayt 8..11 = kare kimliği, hepsi `aesIvMask` ile XOR), `net/rtcp/rtcp_builder.cc`
(SR, PLI, Cast geri bildirimi: `CAST` etiketi, ack, kayıp alanları), `sender/frame_sender.cc`, `sender/external_video_encoder.cc`
(kare kimlikleri 0'dan; anahtar kare kendine, diğerleri bir öncekine referans), `components/mirroring/service/session.cc`
(OFFER alanları ve değerleri), `mirror_settings.cc` (400 ms gecikme, ses 48 kHz/10 ms, video 90 kHz). Cast V2 kanalı: pychromecast.
Not: `chromium.googlesource.com` bazı ortamlarda kapalı; `raw.githubusercontent.com/chromium/chromium/<etiket>/<yol>` çalışıyor
(son sürümlerde Cast Streaming Open Screen'e taşındı; eski etiketlerde `media/cast/...` var).

## Sınama
- JVM: `mirror/android/tools/cast-test/` (sahte alıcı, bağımsız yeniden yazılmış çözücü). "SONUÇ: BAŞARILI" beklenir.
- Gerçek cihaz: telefonu ve Android TV/Chromecast'i aynı Wi-Fi'a al, iMirror'da cihaz "model · Google Cast" olarak çıkar.
  `adb logcat -s ScreenMirrorCast` her adımı yazar (`→` giden, `←` gelen mesajlar; ANSWER'ın tamamı da).

## Gerçek cihazda bozulabilecek yerler (öncelik sırasıyla)
1. LAUNCH `0F5096E8` reddedilir / alıcı Chrome dışı göndericiyi kabul etmez (cihaz kimlik doğrulaması istenebilir:
   `urn:x-cast:com.google.cast.tp.deviceauth`; şu an uygulanmadı).
2. OFFER alanları: yeni alıcılar Open Screen şemasını bekleyebilir (`codecParameter`, `rtpExtensions` biçimi vb.); ANSWER hata dönerse
   günlükteki `error` alanına bak.
3. Alıcı H.264'ü reddeder / siyah ekran: seviye/profil (şu an Baseline L4.1), anahtar kare sıklığı (2 sn), SPS/PPS yerleşimi.
4. Ses: Opus kodlayıcı yok/uyumsuz, ses-görüntü kayması (SR eşlemesi `onOpus` içinde, `t0`'a göre).
5. Gecikme: `targetDelay` = ayarlardaki gecikme payı (100-1000 ms); alıcı en düşük değeri kendi sınırlar.
6. Kayıp/duraklama: pacing (`PACE_EVERY`), paket boyutu (1200), NACK yeniden gönderimi.
