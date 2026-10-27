# Cast (Chromecast built-in / Android TV) yansıtma planı (C seçeneği)

Hedef: TV'ye hiçbir uygulama kurmadan, Android TV / Chromecast'in **yerleşik Cast alıcısına** iMirror'dan ekran yansıtmak
(Chrome'un "Cast ekranı/sekme" yansıtmasının kullandığı protokol: **Cast Streaming**, "Chrome Mirroring" alıcı uygulaması).

Durum: **kod yok.** Bulut oturumunda Open Screen kaynağına (chromium.googlesource.com) erişilemedi ve gerçek Chromecast
yoktu; protokol ayrıntılarını uydurmamak için yazmadım. Yerel makinede (kaynağa erişimli, TV ile aynı ağda) yapılmalı.

## Bilinenler (kaynaklı)
- Chrome'un kullandığı alıcı uygulaması "Chrome Mirroring", uygulama kimliği `0F5096E8`; `urn:x-cast:com.google.cast.webrtc`
  ve `urn:x-cast:com.google.cast.media` ad alanlarını duyurur (node-castv2-client issue #14'te gerçek bir OFFER örneği var).
- OFFER içinde `supportedStreams` dizisi; her akışta `aesKey`, `aesIvMask`, `ssrc`, `rtpPayloadType`, `rtpProfile: "cast"`, `timeBase`;
  yeni sürümlerde `codecParameter`, `rtpExtensions` (örn. `adaptive_playout_delay`), `renderMode`, `receiverRtcpEventLog`, `castMode: "mirroring"`.
- Akış: Cast V2 kontrol kanalı (TLS, 8009) -> `LAUNCH` -> alıcıya OFFER -> ANSWER (UDP portu, ssrc'ler) -> şifreli, RTP benzeri
  (Cast RTP) UDP akışı + RTCP geri bildirimi.
- Kaynaklar: Open Screen "Cast Streaming" README'si https://chromium.googlesource.com/openscreen/+/HEAD/cast/streaming/README.md ,
  https://github.com/thibauts/node-castv2-client/issues/14 , pychromecast (Cast V2 kontrol kanalı).

## Bilinmeyenler (kaynaktan okunmalı, tahmin edilmemeli)
- Cast RTP başlığının bit düzeni (anahtar kare bayrağı, kare/paket kimliği, referans kare, uzantılar), AES-CTR IV üretimi.
- RTCP: Sender Report, alıcı geri bildirimi (ACK/NACK, PLI), gecikme (playout delay) uzantısı.
- ANSWER şeması ve alıcının kabul ettiği H.264 profili/seviyesi (Baseline L4.1/4.2 yeterli mi), ses için Opus parametreleri.
- Alıcının göndericiyi doğrulayıp doğrulamadığı (device auth / sender cert) - Chrome dışı gönderici kabul ediliyor mu?

## Uygulama mimarisi (öneri)
1. `cast/CastChannel.kt`: TLS soketi (sertifika doğrulaması kapalı), `CastMessage` protobuf çerçeveleme (4 bayt uzunluk), CONNECT, PING/PONG, GET_STATUS, LAUNCH.
2. `cast/CastDiscovery.kt`: NSD `_googlecast._tcp` (TXT: `fn` = ad, `md` = model).
3. `cast/CastSession.kt`: OFFER/ANSWER, anahtar üretimi, akış başlatma.
4. `cast/CastRtpSender.kt`: bizim `ScreenEncoder` çıktısı (`FrameSink`) -> kare paketleme + AES-CTR + RTCP SR/geri bildirim; sinyalde anahtar kare isteği `requestKeyFrame()`.
5. Ses: Opus (MediaCodec `audio/opus` kodlayıcı, 48 kHz; AudioCapturer 48 kHz'e alınır).
6. Arayüz: Cast cihazları listede `Kind.CAST` olarak, Premium/filigran mantığı aynen (frame zaten GL katmanından geçiyor).

## Test yöntemi
- Aynı ağda gerçek Android TV / Chromecast. Chrome'dan "Cast" ile TV'ye yansıtırken Wireshark yakalaması (UDP akışı + 8009 TLS
  kanalı) referans olarak alınır; kendi gönderimizin paketleri onunla karşılaştırılır.
- Önce yalnızca kontrol kanalı + LAUNCH + OFFER/ANSWER denenir (alıcı ne cevap veriyor, günlüğe yaz), sonra tek bir video akışı, sonra ses.
- `mirror/android/tvreceiver` (kendi alıcımız) geri dönüş seçeneği olarak duruyor; Cast çalışırsa gerekmez.
