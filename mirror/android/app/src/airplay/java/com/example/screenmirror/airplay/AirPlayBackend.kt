package com.example.screenmirror.airplay

import com.example.screenmirror.AirPlayTarget
import com.example.screenmirror.FrameSink
import com.example.screenmirror.MirrorBackend
import sender.Sender

/**
 * Go tarafındaki `sender` paketinin (gomobile AAR'ı) Kotlin sarmalayıcısı.
 * Bu kaynak seti sadece app/libs/airplay.aar varsa derlenir (bkz. app/build.gradle.kts).
 *
 * gomobile eşlemesi: Go `int` -> Java `long`; `NewSession` -> `Sender.newSession`.
 */
class AirPlayBackend(
    target: AirPlayTarget,
    savedCreds: String,
    private val cb: MirrorBackend.Callbacks,
) : MirrorBackend {

    private val goListener = object : sender.Listener {
        override fun onStatus(state: String, detail: String) = cb.onStatus(state, detail)
        override fun onPinRequired() = cb.onPinRequired()
        override fun onVideoCanvas(width: Long, height: Long) = cb.onCanvas(width.toInt(), height.toInt())
        override fun onNeedKeyframe() = cb.onNeedKeyframe()
        override fun onCredentials(deviceID: String, json: String) = cb.onCredentials(deviceID, json)
    }

    private val session = Sender.newSession(
        target.name, target.ip, target.port.toLong(), target.txt, savedCreds, goListener,
    )

    @Volatile private var audioEnabled = true

    override val sink: FrameSink = object : FrameSink {
        override fun sendFrame(data: ByteArray, size: Int, keyframe: Boolean) {
            session.pushFrame(if (size == data.size) data else data.copyOf(size), keyframe)
        }

        override val wantsAudio: Boolean get() = audioEnabled

        override fun sendAudio(pcm: ByteArray, size: Int) {
            session.pushAudio(if (size == pcm.size) pcm else pcm.copyOf(size))
        }

        override fun close() = session.stop()
    }

    override fun run() {
        try {
            session.run()
        } catch (e: Exception) {
            // Go tarafı hatayı zaten OnStatus("error") ile bildirir
            e.printStackTrace()
        }
    }

    override fun submitPin(pin: String) = session.submitPin(pin)

    override fun setAudioEnabled(on: Boolean) {
        audioEnabled = on
        session.setAudioEnabled(on)
    }

    override fun setLatencyMs(ms: Int) = Sender.setLatencyMs(ms.toLong())

    override fun stop() = session.stop()

    companion object {
        @JvmStatic
        fun version(): String = Sender.version()
    }
}
