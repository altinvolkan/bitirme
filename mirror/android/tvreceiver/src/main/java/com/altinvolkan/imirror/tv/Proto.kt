package com.altinvolkan.imirror.tv

/**
 * Tel protokolü (telefondaki `Protocol.kt` ve tvOS `Proto` ile birebir aynı olmalı).
 *
 * Video paketi (big-endian):
 *   [0] type=1  [1] flags (bit0 = keyframe)  [2..5] frameId u32
 *   [6..7] fragIdx u16  [8..9] fragCount u16  [10..] H.264 Annex-B parçası
 * Ses paketi: [0] type=3  [1] 0  [2..5] seq u32  [6..] PCM S16LE, 44100 Hz, stereo
 * Geri kanal (TV -> telefon): tek byte, type=2 => "keyframe gönder".
 */
object Proto {
    const val PORT = 52000
    const val SERVICE_TYPE = "_scrmirror._udp"

    const val TYPE_VIDEO: Byte = 1
    const val TYPE_KEYFRAME_REQUEST: Byte = 2
    const val TYPE_AUDIO: Byte = 3
    const val FLAG_KEYFRAME = 1

    const val VIDEO_HEADER = 10
    const val AUDIO_HEADER = 6

    const val SAMPLE_RATE = 44100
}
