package com.example.screenmirror

/**
 * Tel protokolü (tvOS tarafındaki `Proto` ile birebir aynı olmalı).
 *
 * Video paketi (big-endian):
 *   [0] type=1  [1] flags (bit0 = keyframe)  [2..5] frameId u32
 *   [6..7] fragIdx u16  [8..9] fragCount u16  [10..] H.264 Annex-B parçası
 *
 * Ses paketi (yalnızca Android TV alıcısı çalar): [0] type=3  [1] 0  [2..5] seq u32
 *   [6..] PCM S16LE, 44100 Hz, stereo (paket başına en çok [AUDIO_MAX_PAYLOAD] bayt)
 *
 * Geri kanal (alıcı -> Android): tek byte, type=2 => "keyframe gönder".
 */
object Protocol {
    const val DEFAULT_PORT = 52000
    const val SERVICE_TYPE = "_scrmirror._udp"

    const val TYPE_VIDEO: Byte = 1
    const val TYPE_KEYFRAME_REQUEST: Byte = 2
    const val TYPE_AUDIO: Byte = 3
    const val FLAG_KEYFRAME: Byte = 1

    const val HEADER_SIZE = 10
    const val MTU = 1200
    const val MAX_PAYLOAD = MTU - HEADER_SIZE

    const val AUDIO_HEADER = 6
    const val AUDIO_MAX_PAYLOAD = 1184 // 4 baytın (stereo S16 örneği) katı; MTU'ya sığar
}
