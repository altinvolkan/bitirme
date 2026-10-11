package com.example.screenmirror

/**
 * Tel protokolü (tvOS tarafındaki `Proto` ile birebir aynı olmalı).
 *
 * Video paketi (big-endian):
 *   [0] type=1  [1] flags (bit0 = keyframe)  [2..5] frameId u32
 *   [6..7] fragIdx u16  [8..9] fragCount u16  [10..] H.264 Annex-B parçası
 *
 * Geri kanal (tvOS -> Android): tek byte, type=2 => "keyframe gönder".
 */
object Protocol {
    const val DEFAULT_PORT = 52000
    const val SERVICE_TYPE = "_scrmirror._udp"

    const val TYPE_VIDEO: Byte = 1
    const val TYPE_KEYFRAME_REQUEST: Byte = 2
    const val FLAG_KEYFRAME: Byte = 1

    const val HEADER_SIZE = 10
    const val MTU = 1200
    const val MAX_PAYLOAD = MTU - HEADER_SIZE
}
