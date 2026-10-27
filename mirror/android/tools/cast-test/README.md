# Cast gönderici sınaması (Android'siz)

`CastChannel` (TLS + protobuf), `CastStream` (Cast RTP + AES-CTR + RTCP) ve `CastBackend` (LAUNCH/OFFER/ANSWER akışı) JVM'de
derlenir ve Python ile yazılmış **sahte bir Cast alıcısı**na karşı çalıştırılır. Sahte alıcı Chromium kaynağından
(`media/cast/net/rtp/rtp_packetizer.cc`, `rtcp_builder.cc`, `common/transport_encryption_handler.cc`,
`components/mirroring/service/session.cc`) bağımsız olarak yeniden yazıldı: başlık bitleri, kare kimlikleri, AES nonce'u,
SR, PLI, Cast ACK/NACK ve yeniden gönderim doğrulanır.

Bu **gerçek bir Chromecast/Android TV'nin davranışını kanıtlamaz**; yalnızca gönderici kodunun kendi içinde tutarlı olduğunu
ve Chromium kaynağındaki biçimlere uyduğunu gösterir. Gerçek cihaz denemesi için `docs/CAST_MIRRORING_PLAN.md`.

```
python prepare.py <kotlinc> <org.json jar>     # kotlinc 1.9.x, json-20240303.jar gibi
python fake_receiver.py                        # "SONUÇ: BAŞARILI" beklenir
```
