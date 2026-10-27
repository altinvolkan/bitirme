package com.example.screenmirror

import java.io.Closeable

/** Encoder çıktısının gittiği yer: kendi UDP protokolümüz veya AirPlay. */
interface FrameSink : Closeable {
    /** Annex-B access unit; keyframe ise SPS+PPS başta. Encoder thread'inden çağrılır. */
    fun sendFrame(data: ByteArray, size: Int, keyframe: Boolean)

    /** Sistem sesini (S16LE, 44.1 kHz, stereo) kabul ediyor mu? */
    val wantsAudio: Boolean get() = false

    /** Ses yakalama örnekleme hızı (AirPlay 44100, Cast/Opus 48000). */
    val audioSampleRate: Int get() = 44100

    /** PCM parçası; sadece [wantsAudio] true ise çağrılır. */
    fun sendAudio(pcm: ByteArray, size: Int) {}
}
