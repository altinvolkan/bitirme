import com.example.screenmirror.MirrorBackend
import com.example.screenmirror.cast.CastBackend
import com.example.screenmirror.cast.CastTarget
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.util.Random
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val port = args[0].toInt()
    val sentFile = args[1]
    val keyReqs = java.util.concurrent.atomic.AtomicInteger()
    val canvas = java.util.concurrent.CountDownLatch(1)
    val cb = object : MirrorBackend.Callbacks {
        override fun onCanvas(width: Int, height: Int) { println("STATUS canvas $width x $height"); canvas.countDown() }
        override fun onNeedKeyframe() { println("STATUS keyframe-request"); keyReqs.incrementAndGet() }
        override fun onStatus(state: String, detail: String) { println("STATUS $state $detail") }
        override fun onPinRequired() {}
        override fun onCredentials(deviceId: String, json: String) {}
    }
    val b = CastBackend(CastTarget("Test TV", "127.0.0.1", port, "Fake", "id"), cb, 8_000_000, 30)
    b.setLatencyMs(250)
    b.setAudioEnabled(false)
    thread(name = "session") { b.run() }
    if (!canvas.await(20, java.util.concurrent.TimeUnit.SECONDS)) { println("FAIL no canvas"); System.exit(2) }
    Thread.sleep(300)
    val rnd = Random(42)
    DataOutputStream(FileOutputStream(sentFile)).use { out ->
        for (n in 0 until 40) {
            val key = n % 15 == 0
            val sizes = if (key) 9000 else if (n % 7 == 3) 4000 else 700
            val body = ByteArray(sizes).also { rnd.nextBytes(it) }
            val hdr = if (key) byteArrayOf(0,0,0,1,0x67,0x42,0x00,0x1f,0,0,0,1,0x68,0x00,0,0,0,1,0x65) else byteArrayOf(0,0,0,1,0x41)
            val frame = hdr + body
            out.writeInt(frame.size); out.writeBoolean(key); out.write(frame)
            b.sink.sendFrame(frame, frame.size, key)
            Thread.sleep(33)
        }
    }
    Thread.sleep(1500)
    println("DONE keyframe-requests=${keyReqs.get()}")
    b.stop()
    Thread.sleep(500)
    System.exit(0)
}
