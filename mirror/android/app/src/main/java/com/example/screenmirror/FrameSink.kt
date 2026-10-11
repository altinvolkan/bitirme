package com.example.screenmirror

import java.io.Closeable

/** Encoder çıktısının gittiği yer: kendi UDP protokolümüz veya AirPlay. */
interface FrameSink : Closeable {
    /** Annex-B access unit; keyframe ise SPS+PPS başta. Encoder thread'inden çağrılır. */
    fun sendFrame(data: ByteArray, size: Int, keyframe: Boolean)
}
