package com.example.screenmirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * satın alım olursa filigran çizimi anında durur.
 *
 * Filigran: sağ altta küçük bir "iMirror" etiketi (hep görünür) + belirli aralıklarla kısa süre
 * beliren "Premium ile kaldır" şeridi. Kurulum başarısız olursa [start] null döner ve çağıran
 * doğrudan encoder yüzeyini kullanır (filigransız, yayın bozulmaz).
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
    private var chipTex = 0
    private var bannerTex = 0
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
    private var drawnBannerAlpha = 0f
    @Volatile private var released = false

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val a = if (Entitlements.premium) 0f else bannerAlpha()
            // Ekran durağanken de şerit canlansın: kaynak kare gelmese de yeniden çiz
            if (a > 0f || drawnBannerAlpha > 0f) renderFrame(false)
            handler.postDelayed(this, if (a > 0f) 66L else 500L)
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

        chipTex = upload(chipBitmap())
        bannerTex = upload(bannerBitmap())

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
            if (!Entitlements.premium) drawOverlays() else drawnBannerAlpha = 0f
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
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val ref = min(width.toFloat(), height * 1.2f)
        val margin = ref * 0.03f

        // etiket: sağ alt
        val chipW = ref * 0.30f
        val chipH = chipW * CHIP_H / CHIP_W
        drawQuad(chipTex, width - margin - chipW, margin, chipW, chipH, 0.85f)

        // şerit: etiketin üstünde, ortada; zamanlı belirir
        val a = bannerAlpha()
        drawnBannerAlpha = a
        if (a > 0f) {
            val bw = ref * 0.9f
            val bh = bw * BANNER_H / BANNER_W
            drawQuad(bannerTex, (width - bw) / 2f, margin * 2f + chipH, bw, bh, a)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** (x, y) sol alt köşe, piksel; y yukarı doğru. */
    private fun drawQuad(tex: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        val l = x / width * 2f - 1f
        val r = (x + w) / width * 2f - 1f
        val b = y / height * 2f - 1f
        val t = (y + h) / height * 2f - 1f
        quadPos.position(0)
        quadPos.put(floatArrayOf(l, b, r, b, l, t, r, t))
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

    /** Şerit: ilk gösterim [FIRST_MS]'de, sonra her [PERIOD_MS]'de [SHOW_MS] süreyle (yumuşak giriş/çıkış). */
    private fun bannerAlpha(): Float {
        val t = SystemClock.elapsedRealtime() - startMs
        if (t < FIRST_MS) return 0f
        val p = (t - FIRST_MS) % PERIOD_MS
        if (p >= SHOW_MS) return 0f
        return min(1f, min(p / FADE_MS, (SHOW_MS - p) / FADE_MS)).coerceIn(0f, 1f)
    }

    // --- bitmap'ler ---

    private fun chipBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(CHIP_W.toInt(), CHIP_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(2f, 2f, CHIP_W - 2f, CHIP_H - 2f)
        p.color = (200 shl 24) or (C.BG and 0xFFFFFF)
        c.drawRoundRect(rect, CHIP_H / 2, CHIP_H / 2, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = C.LINE2
        c.drawRoundRect(rect, CHIP_H / 2, CHIP_H / 2, p)
        p.style = Paint.Style.FILL
        p.color = C.ACCENT
        c.drawCircle(CHIP_H / 2f + 4f, CHIP_H / 2f, 11f, p)
        p.color = C.TEXT
        p.typeface = Fonts.display(700)
        p.textSize = 46f
        c.drawText(context.getString(R.string.app_name), CHIP_H / 2f + 30f, CHIP_H / 2f + 16f, p)
        return bmp
    }

    private fun bannerBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(BANNER_W.toInt(), BANNER_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(3f, 3f, BANNER_W - 3f, BANNER_H - 3f)
        p.color = (240 shl 24) or (C.GOLD_TINT and 0xFFFFFF)
        c.drawRoundRect(rect, BANNER_H / 2, BANNER_H / 2, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = C.GOLD
        c.drawRoundRect(rect, BANNER_H / 2, BANNER_H / 2, p)
        p.style = Paint.Style.FILL
        p.color = C.GOLD
        c.drawCircle(BANNER_H / 2f + 8f, BANNER_H / 2f, 14f, p)
        p.typeface = Fonts.body(700)
        val text = context.getString(R.string.wm_banner)
        val maxW = BANNER_W - BANNER_H - 60f
        var size = 56f
        p.textSize = size
        while (p.measureText(text) > maxW && size > 20f) {
            size -= 2f
            p.textSize = size
        }
        c.drawText(text, BANNER_H / 2f + 38f, BANNER_H / 2f + size * 0.35f, p)
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
        private const val CHIP_W = 360f
        private const val CHIP_H = 96f
        private const val BANNER_W = 1000f
        private const val BANNER_H = 150f
        private const val FIRST_MS = 40_000L
        private const val PERIOD_MS = 150_000L
        private const val SHOW_MS = 10_000f
        private const val FADE_MS = 700f

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
