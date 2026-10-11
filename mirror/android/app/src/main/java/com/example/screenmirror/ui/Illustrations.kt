package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

enum class Art { LINK, WIFI, FIVE_G, NEAR, ETHERNET, SCOPE, DRM }

/**
 * Tanıtım ve bağlanma ekranlarındaki çizimler. 320×220'lik tasarım kutusunda çizilir,
 * görünüme sığacak şekilde ölçeklenir; hafif bir döngü animasyonu vardır.
 */
class Illustration(context: Context, private val art: Art, private val animate: Boolean = true) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var phase = 0.3f
    private val tick = object : Runnable {
        override fun run() {
            phase = (SystemClock.uptimeMillis() % 2400L) / 2400f
            invalidate()
            postDelayed(this, 66) // ~15 kare/sn: göz için yeter, telefon ekranını yansıtırken encoder'ı boşuna yormaz
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animate) post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val s = min(width / 320f, height / 220f)
        c.save()
        c.translate((width - 320 * s) / 2f, (height - 220 * s) / 2f)
        c.scale(s, s)
        when (art) {
            Art.LINK -> link(c)
            Art.WIFI -> wifi(c)
            Art.FIVE_G -> fiveG(c)
            Art.NEAR -> near(c)
            Art.ETHERNET -> ethernet(c)
            Art.SCOPE -> scope(c)
            Art.DRM -> drm(c)
        }
        c.restore()
    }

    // --- çizim yardımcıları ---

    private fun fill(color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
    }

    private fun stroke(color: Int, w: Float) {
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, f: Int, st: Int = 0, sw: Float = 2f) {
        rect.set(l, t, r, b)
        fill(f)
        c.drawRoundRect(rect, rad, rad, p)
        if (st != 0) {
            stroke(st, sw)
            c.drawRoundRect(rect, rad, rad, p)
        }
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, f: Int) {
        fill(f)
        c.drawCircle(x, y, r, p)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float) {
        stroke(color, w)
        c.drawLine(x1, y1, x2, y2, p)
    }

    private fun s(id: Int): String = context.getString(id)

    /** [maxW] verilirse metin bu genişliğe sığacak kadar küçültülür (uzun çeviriler için). */
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, weight: Int = 600, align: Paint.Align = Paint.Align.CENTER, maxW: Float = 0f) {
        p.style = Paint.Style.FILL
        p.color = color
        p.textSize = size
        p.textAlign = align
        p.typeface = Fonts.body(weight)
        if (maxW > 0f) {
            val m = p.measureText(s)
            if (m > maxW) p.textSize = size * maxW / m
        }
        c.drawText(s, x, y, p)
    }

    private fun arc(c: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float, color: Int, w: Float) {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke(color, w)
        c.drawArc(rect, start, sweep, false, p)
    }

    /** Sırayla yanan dalga yayları. */
    private fun waves(c: Canvas, cx: Float, cy: Float, start: Float, sweep: Float, color: Int, count: Int, r0: Float, step: Float) {
        val k = (phase * (count + 1)).toInt()
        for (i in 0 until count) {
            val a = if (k >= i) 255 else 70
            arc(c, cx, cy, r0 + step * i, start, sweep, (color and 0x00FFFFFF) or (a shl 24), 5f)
        }
    }

    private fun phone(c: Canvas, l: Float, t: Float, w: Float, h: Float, screen: Int = C.SURFACE2) {
        rr(c, l, t, l + w, t + h, 12f, C.SURFACE, C.LINE2, 3f)
        rr(c, l + 5, t + 12, l + w - 5, t + h - 12, 4f, screen)
        line(c, l + w / 2 - 6, t + 6, l + w / 2 + 6, t + 6, C.LINE2, 3f)
    }

    private fun tv(c: Canvas, l: Float, t: Float, w: Float, h: Float, screen: Int = C.ACCENT_TINT) {
        rr(c, l, t, l + w, t + h, 8f, C.SURFACE, C.LINE2, 3f)
        rr(c, l + 5, t + 5, l + w - 5, t + h - 5, 4f, screen)
        line(c, l + w / 2 - 14, t + h + 8, l + w / 2 + 14, t + h + 8, C.LINE2, 4f)
        line(c, l + w / 2, t + h, l + w / 2, t + h + 8, C.LINE2, 3f)
    }

    private fun playTriangle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        path.moveTo(cx - r * 0.6f, cy - r)
        path.lineTo(cx + r, cy)
        path.lineTo(cx - r * 0.6f, cy + r)
        path.close()
        fill(color)
        c.drawPath(path, p)
    }

    private fun router(c: Canvas, l: Float, t: Float, w: Float, h: Float) {
        line(c, l + 12, t, l + 4, t - 22, C.LINE2, 4f)
        line(c, l + w - 12, t, l + w - 4, t - 22, C.LINE2, 4f)
        rr(c, l, t, l + w, t + h, 10f, C.SURFACE2, C.LINE2, 3f)
        for (i in 0..2) circle(c, l + 16 + i * 12f, t + h / 2, 3.2f, if (i == 0 || (phase > 0.5f && i == 1)) C.ACCENT else C.LINE2)
    }

    private fun pill(c: Canvas, cx: Float, cy: Float, w: Float, label: String, f: Int, st: Int, color: Int, size: Float = 15f) {
        rr(c, cx - w / 2, cy - 15, cx + w / 2, cy + 15, 15f, f, st, 2f)
        text(c, label, cx, cy + size * 0.35f, size, color, 700, maxW = w - 18f)
    }

    private fun checkBadge(c: Canvas, cx: Float, cy: Float, r: Float = 13f) {
        circle(c, cx, cy, r, C.ACCENT)
        stroke(C.ACCENT_DARK, 3f)
        path.reset()
        path.moveTo(cx - r * 0.45f, cy)
        path.lineTo(cx - r * 0.1f, cy + r * 0.38f)
        path.lineTo(cx + r * 0.5f, cy - r * 0.35f)
        c.drawPath(path, p)
    }

    // --- sahneler ---

    private fun link(c: Canvas) {
        phone(c, 40f, 50f, 72f, 128f)
        rr(c, 50f, 74f, 102f, 154f, 4f, C.ACCENT_TINT)
        tv(c, 188f, 62f, 104f, 72f)
        playTriangle(c, 240f, 98f, 11f, C.ACCENT)
        for (i in 0..4) {
            val t = (phase + i / 5f) % 1f
            val a = (255 * (if (t < 0.5f) t * 2 else (1 - t) * 2)).toInt()
            circle(c, 124 + 52 * t, 114f, 4.5f, (C.ACCENT and 0x00FFFFFF) or (a shl 24))
        }
    }

    private fun wifi(c: Canvas) {
        phone(c, 30f, 62f, 62f, 112f)
        tv(c, 224f, 78f, 76f, 54f)
        waves(c, 160f, 128f, 225f, 90f, C.ACCENT, 3, 22f, 20f)
        circle(c, 160f, 128f, 5.5f, C.ACCENT)
        pill(c, 160f, 180f, 130f, s(R.string.art_same_wifi), C.ACCENT_TINT, C.ACCENT, C.ACCENT)
        checkBadge(c, 160f, 36f)
    }

    private fun fiveG(c: Canvas) {
        pill(c, 92f, 40f, 112f, "2.4 GHz", 0, C.LINE2, C.DIM)
        line(c, 44f, 40f, 140f, 40f, C.DIM, 2f)
        pill(c, 228f, 40f, 112f, "5 GHz", C.ACCENT, 0, C.ACCENT_DARK, 17f)
        waves(c, 160f, 150f, 235f, 70f, C.ACCENT, 3, 36f, 24f)
        router(c, 112f, 150f, 96f, 36f)
    }

    private fun near(c: Canvas) {
        router(c, 28f, 150f, 92f, 38f)
        waves(c, 74f, 146f, -75f, 60f, C.ACCENT, 4, 40f, 30f)
        // yakın telefon: tam sinyal
        phone(c, 196f, 44f, 62f, 112f)
        for (i in 0..3) {
            val h = 8f + i * 7f
            rr(c, 206f + i * 10f, 118f - h, 212f + i * 10f, 118f, 2f, C.ACCENT)
        }
        // uzak (silik) telefon
        rr(c, 270f, 80f, 306f, 150f, 8f, 0, C.LINE, 2f)
        rr(c, 281f, 128f, 285f, 134f, 1f, C.DIM)
        text(c, s(R.string.art_near), 227f, 178f, 14f, C.ACCENT, 700, maxW = 80f)
        text(c, s(R.string.art_far), 288f, 178f, 14f, C.DIM, 600, maxW = 60f)
    }

    private fun ethernet(c: Canvas) {
        tv(c, 176f, 14f, 104f, 66f)
        playTriangle(c, 228f, 47f, 10f, C.ACCENT)
        // Apple TV kutusu
        rr(c, 168f, 118f, 296f, 156f, 10f, 0xFF0B0C10.toInt(), C.LINE2, 3f)
        line(c, 180f, 124f, 284f, 124f, C.LINE2, 2f)
        circle(c, 184f, 144f, 3.5f, C.ACCENT)
        text(c, "tv", 232f, 148f, 14f, C.DIM, 700)
        // HDMI
        line(c, 228f, 118f, 228f, 90f, C.LINE2, 4f)
        // router
        router(c, 24f, 150f, 86f, 36f)
        // ethernet kablosu
        path.reset()
        path.moveTo(110f, 168f)
        path.cubicTo(135f, 168f, 140f, 140f, 166f, 140f)
        stroke(C.ACCENT, 4f)
        c.drawPath(path, p)
        rr(c, 106f, 163f, 116f, 173f, 2f, C.ACCENT)
        rr(c, 162f, 135f, 172f, 145f, 2f, C.ACCENT)
        for (i in 0..2) {
            val t = (phase + i / 3f) % 1f
            val x = 112 + 54 * t
            val y = 168 - 28 * t
            circle(c, x, y, 3.2f, C.TEXT)
        }
        pill(c, 100f, 100f, 112f, "Ethernet", C.ACCENT_TINT, C.ACCENT, C.ACCENT)
    }

    private fun scope(c: Canvas) {
        // Android'in "ekran paylaş" penceresinin alt kısmı
        val sheet = 0xFF25272C.toInt()
        rr(c, 12f, 4f, 308f, 240f, 26f, sheet)
        text(c, s(R.string.art_scope), 36f, 42f, 17f, C.TEXT, 700, Paint.Align.LEFT, maxW = 80f)
        text(c, s(R.string.scope_entire) + "  ⇅", 292f, 42f, 14f, C.MUTED, 600, Paint.Align.RIGHT, maxW = 175f)
        val glow = (120 + 135 * (if (phase < 0.5f) phase * 2 else (1 - phase) * 2)).toInt()
        rr(c, 24f, 20f, 296f, 56f, 14f, 0, (C.ACCENT and 0x00FFFFFF) or (glow shl 24), 3f)
        // açılan seçenekler
        rr(c, 84f, 62f, 298f, 132f, 14f, 0xFF35373D.toInt(), C.LINE2, 2f)
        checkBadge(c, 104f, 86f, 9f)
        text(c, s(R.string.scope_entire), 124f, 91f, 15f, C.TEXT, 700, Paint.Align.LEFT, maxW = 114f)
        rr(c, 244f, 74f, 288f, 92f, 9f, C.ACCENT_TINT)
        text(c, s(R.string.art_best), 266f, 87f, 10f, C.ACCENT, 700, maxW = 40f)
        line(c, 96f, 104f, 286f, 104f, C.LINE2, 1.5f)
        text(c, s(R.string.scope_single), 124f, 125f, 15f, C.MUTED, 600, Paint.Align.LEFT, maxW = 164f)
        // açıklama yer tutucu çizgileri
        rr(c, 32f, 144f, 288f, 150f, 3f, C.LINE2)
        rr(c, 32f, 156f, 240f, 162f, 3f, C.LINE2)
        // düğmeler
        rr(c, 24f, 172f, 150f, 216f, 22f, 0xFF3A3D44.toInt())
        text(c, s(R.string.action_cancel), 87f, 200f, 16f, C.TEXT, 700, maxW = 106f)
        rr(c, 162f, 172f, 296f, 216f, 22f, 0xFF2B7BFF.toInt())
        text(c, s(R.string.art_share), 229f, 200f, 16f, 0xFFFFFFFF.toInt(), 700, maxW = 114f)
    }

    private fun drm(c: Canvas) {
        phone(c, 124f, 22f, 76f, 136f, 0xFF0B0C10.toInt())
        // kilit
        rr(c, 144f, 78f, 180f, 108f, 6f, C.LIVE)
        arc(c, 162f, 78f, 11f, 180f, 180f, C.LIVE, 5f)
        circle(c, 162f, 93f, 4f, C.LIVE_DARK)
        // yasak rozeti
        circle(c, 206f, 54f, 24f, C.LIVE_TINT)
        stroke(C.LIVE, 4f)
        c.drawCircle(206f, 54f, 24f, p)
        line(c, 190f, 70f, 222f, 38f, C.LIVE, 4f)
        pill(c, 72f, 188f, 104f, "Netflix", 0, C.LIVE, C.LIVE_TEXT)
        pill(c, 248f, 188f, 104f, "Spotify", 0, C.LIVE, C.LIVE_TEXT)
        text(c, s(R.string.art_and_more), 160f, 196f, 12f, C.DIM, 500, maxW = 68f)
    }
}
