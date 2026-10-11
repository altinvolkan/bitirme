package com.example.screenmirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.screenmirror.monetization.Entitlements
import com.example.screenmirror.ui.C
import com.example.screenmirror.ui.Fonts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.min

/**
 * Ücretsiz sürümde görüntüye iMirror filigranı basar.
 *
 * VirtualDisplay -> [inputSurface] (SurfaceTexture) -> GL ile çiz + filigran -> [output]
 * (MediaCodec'in input surface'i). Premium kullanıcıda bu sınıf hiç kurulmaz; kurulduktan sonra
 * satın alım veya ödüllü reklam olursa filigran çizimi anında durur.
 *
 * Aşamalar (yayın başlangıcından itibaren):
 *  - 0-10 dk: üstte ve altta silik "iMirror · Ücretsiz kullanım" yazısı.
 *  - 10 dk+: yazı "Reklam ve filigranlardan kurtul · <fiyat>/ay" olur (altın rengi, daha belirgin)
 *    ve görüntüye çapraz "iMirror" filigranı biner (%10; 30 dk'dan sonra %30).
 * Kurulum başarısız olursa [start] null döner ve çağıran doğrudan encoder yüzeyini kullanır
 * (filigransız, yayın bozulmaz).
 */
class WatermarkRenderer(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val output: Surface,
) {
    private val thread = HandlerThread("wm-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var oesTex = 0
    private var stripTex = 0
    private var diagTex = 0
    private var upsellShown = false
    private var oesProg = 0
    private var ovProg = 0
    private var oesPos = 0
    private var oesUv = 0
    private var oesMatLoc = 0
    private var oesSamplerLoc = 0
    private var ovPos = 0
    private var ovUv = 0
    private var ovSamplerLoc = 0
    private var ovAlphaLoc = 0

    private val fullPos = fb(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val fullUv = fb(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val quadPos = fb(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val quadUv = fb(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f) // bitmap üstü t=0
    private val stMatrix = FloatArray(16)

    private val startMs = SystemClock.elapsedRealtime()
    private var haveFrame = false
    private var lastPtsNs = 0L
    private var drawnKey = -1
    @Volatile private var released = false

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            // Durum (aşama / çapraz filigran saydamlığı) değiştiyse, ekran durağan olsa da yeniden çiz
            if (stateKey() != drawnKey) renderFrame(false)
            handler.postDelayed(this, 500L)
        }
    }

    /** GL kurulumunu yapar ve VirtualDisplay'in çizeceği yüzeyi döndürür; başarısızsa null. */
    fun start(): Surface? {
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = try {
                setup()
                true
            } catch (e: Throwable) {
                Log.w(TAG, "filigran GL kurulamadı, filigransız devam: $e")
                teardown()
                false
            }
            latch.countDown()
        }
        latch.await()
        if (!ok) {
            thread.quitSafely()
            return null
        }
        handler.postDelayed(ticker, 500)
        return inputSurface
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post { teardown() }
        thread.quitSafely()
        thread.join(500)
    }

    // --- kurulum ---

    private fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize" }
        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

        oesProg = buildProgram(OES_VS, OES_FS)
        oesPos = GLES20.glGetAttribLocation(oesProg, "aPos")
        oesUv = GLES20.glGetAttribLocation(oesProg, "aUv")
        oesMatLoc = GLES20.glGetUniformLocation(oesProg, "uTex")
        oesSamplerLoc = GLES20.glGetUniformLocation(oesProg, "uTexture")
        ovProg = buildProgram(OV_VS, OV_FS)
        ovPos = GLES20.glGetAttribLocation(ovProg, "aPos")
        ovUv = GLES20.glGetAttribLocation(ovProg, "aUv")
        ovSamplerLoc = GLES20.glGetUniformLocation(ovProg, "uTexture")
        ovAlphaLoc = GLES20.glGetUniformLocation(ovProg, "uAlpha")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        stripTex = upload(stripBitmap(context.getString(R.string.wm_free), C.TEXT))
        diagTex = upload(diagBitmap())

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ renderFrame(true) }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun teardown() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                // eglTerminate çağrılmaz: varsayılan ekran başka bir renderer ile paylaşılıyor olabilir
            }
        } catch (_: Throwable) {
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        try {
            inputSurface?.release()
            surfaceTexture?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        surfaceTexture = null
    }

    // --- çizim ---

    private fun renderFrame(newSource: Boolean) {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
            var ts = System.nanoTime()
            if (newSource) {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                if (st.timestamp > 0) ts = st.timestamp
                haveFrame = true
            }
            if (!haveFrame) return
            if (ts <= lastPtsNs) ts = lastPtsNs + 1_000_000L
            lastPtsNs = ts

            GLES20.glViewport(0, 0, width, height)
            GLES20.glDisable(GLES20.GL_BLEND)
            drawOes()
            if (!Entitlements.watermarkFree) drawOverlays()
            drawnKey = stateKey()
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ts)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Throwable) {
            if (!released) Log.w(TAG, "çizim hatası: $e")
        }
    }

    private fun drawOes() {
        GLES20.glUseProgram(oesProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(oesSamplerLoc, 0)
        GLES20.glUniformMatrix4fv(oesMatLoc, 1, false, stMatrix, 0)
        GLES20.glEnableVertexAttribArray(oesPos)
        GLES20.glVertexAttribPointer(oesPos, 2, GLES20.GL_FLOAT, false, 0, fullPos)
        GLES20.glEnableVertexAttribArray(oesUv)
        GLES20.glVertexAttribPointer(oesUv, 2, GLES20.GL_FLOAT, false, 0, fullUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(oesPos)
        GLES20.glDisableVertexAttribArray(oesUv)
    }

    private fun drawOverlays() {
        val t = SystemClock.elapsedRealtime() - startMs
        if (t >= UPSELL_MS && !upsellShown) {
            // Fiyat o ana kadar Play'den gelmiş olabilir; metni şimdi üret
            upsellShown = true
            GLES20.glDeleteTextures(1, intArrayOf(stripTex), 0)
            stripTex = upload(stripBitmap(upsellText(), C.GOLD))
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val ref = min(width.toFloat(), height * 1.2f)
        val margin = ref * 0.025f

        // üst ve alt şeritler
        val sw = ref * 0.92f
        val sh = sw * STRIP_H / STRIP_W
        val sa = if (upsellShown) 0.85f else 0.45f
        drawQuad(stripTex, (width - sw) / 2f, height - margin - sh, sw, sh, sa, 0f)
        drawQuad(stripTex, (width - sw) / 2f, margin, sw, sh, sa, 0f)

        // çapraz filigran
        val da = diagAlpha(t)
        if (da > 0f) {
            val diag = Math.hypot(width.toDouble(), height.toDouble()).toFloat()
            val cos = Math.cos(Math.toRadians(DIAG_DEG.toDouble())).toFloat()
            val dw = min(diag * 0.55f, width * 0.95f / cos)
            val dh = dw * DIAG_H / DIAG_W
            drawQuad(diagTex, (width - dw) / 2f, (height - dh) / 2f, dw, dh, da, DIAG_DEG)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Aşama + çapraz filigran saydamlığı tek sayıda; değişince yeniden çizilir. */
    private fun stateKey(): Int {
        val t = SystemClock.elapsedRealtime() - startMs
        return (if (Entitlements.watermarkFree) 1000000 else 0) +
            (if (t >= UPSELL_MS) 100000 else 0) + (diagAlpha(t) * 1000f).toInt()
    }

    /** 10 dk'dan sonra %10, 30 dk'dan sonra %30; geçişler 6 sn'de yumuşar. */
    private fun diagAlpha(t: Long): Float {
        fun ramp(from: Long) = ((t - from) / RAMP_MS.toFloat()).coerceIn(0f, 1f)
        return DIAG_A1 * ramp(UPSELL_MS) + (DIAG_A2 - DIAG_A1) * ramp(DIAG2_MS)
    }

    /** (x, y) sol alt köşe, piksel; y yukarı. [deg] merkez etrafında saat yönünün tersine döndürür. */
    private fun drawQuad(tex: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float, deg: Float) {
        val cx = x + w / 2f
        val cy = y + h / 2f
        val rad = Math.toRadians(deg.toDouble())
        val c = Math.cos(rad).toFloat()
        val sn = Math.sin(rad).toFloat()
        // köşeler: sol-alt, sağ-alt, sol-üst, sağ-üst
        val dx = floatArrayOf(-w / 2f, w / 2f, -w / 2f, w / 2f)
        val dy = floatArrayOf(-h / 2f, -h / 2f, h / 2f, h / 2f)
        quadPos.position(0)
        for (i in 0 until 4) {
            val px = cx + dx[i] * c - dy[i] * sn
            val py = cy + dx[i] * sn + dy[i] * c
            quadPos.put(px / width * 2f - 1f)
            quadPos.put(py / height * 2f - 1f)
        }
        quadPos.position(0)
        GLES20.glUseProgram(ovProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(ovSamplerLoc, 0)
        GLES20.glUniform1f(ovAlphaLoc, alpha)
        GLES20.glEnableVertexAttribArray(ovPos)
        GLES20.glVertexAttribPointer(ovPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(ovUv)
        GLES20.glVertexAttribPointer(ovUv, 2, GLES20.GL_FLOAT, false, 0, quadUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(ovPos)
        GLES20.glDisableVertexAttribArray(ovUv)
    }

    private fun upsellText(): String {
        val price = Entitlements.priceText
        return if (price.isEmpty()) context.getString(R.string.wm_upsell_noprice)
        else context.getString(R.string.wm_upsell, context.getString(R.string.price_per_month, price))
    }

    // --- bitmap'ler ---

    /** Üst/alt şerit yazısı: gölgeli düz yazı, uzunsa sığana kadar küçülür. */
    private fun stripBitmap(text: String, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(STRIP_W.toInt(), STRIP_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Fonts.body(700)
        var size = 54f
        p.textSize = size
        val maxW = STRIP_W - 40f
        while (p.measureText(text) > maxW && size > 18f) {
            size -= 2f
            p.textSize = size
        }
        p.textAlign = Paint.Align.CENTER
        p.setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
        p.color = color
        c.drawText(text, STRIP_W / 2f, STRIP_H / 2f + size * 0.35f, p)
        return bmp
    }

    /** Çapraz "iMirror" yazısı (beyaz); saydamlık çizimde verilir. */
    private fun diagBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(DIAG_W.toInt(), DIAG_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Fonts.display(800)
        p.textSize = 300f
        p.color = 0xFFFFFFFF.toInt()
        p.textAlign = Paint.Align.CENTER
        c.drawText(context.getString(R.string.app_name), DIAG_W / 2f, DIAG_H / 2f + 100f, p)
        return bmp
    }

    private fun upload(bmp: Bitmap): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return id[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun fb(vararg f: Float): FloatBuffer =
        ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            it.put(f)
            it.position(0)
        }

    companion object {
        private const val TAG = "ScreenMirrorWm"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val STRIP_W = 1000f
        private const val STRIP_H = 80f
        private const val DIAG_W = 1100f
        private const val DIAG_H = 380f
        private const val DIAG_DEG = 25f

        /** İkinci aşama (satın alma çağrısı + çapraz filigran) ve filigranın koyulaşması. */
        private const val UPSELL_MS = 10 * 60_000L
        private const val DIAG2_MS = 30 * 60_000L
        private const val RAMP_MS = 6_000L
        private const val DIAG_A1 = 0.10f
        private const val DIAG_A2 = 0.30f

        private const val OES_VS = """
            uniform mat4 uTex;
            attribute vec4 aPos;
            attribute vec4 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTex * aUv).xy;
            }
        """
        private const val OES_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv);
            }
        """
        private const val OV_VS = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = aUv;
            }
        """
        private const val OV_FS = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv) * uAlpha;
            }
        """
    }
}
