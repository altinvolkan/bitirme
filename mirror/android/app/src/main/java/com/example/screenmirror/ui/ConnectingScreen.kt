package com.example.screenmirror.ui

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.example.screenmirror.MainActivity

class ConnectingScreen(a: MainActivity, private var stage: Int) : Screen(a) {

    private val steps = listOf("Cihaza ulaşıldı", "Eşleştirme doğrulandı", "Yayın kuruluyor", "Görüntü başlıyor")
    private lateinit var stepsBox: LinearLayout

    override fun build(): View {
        val ctx = a
        val col = ctx.column { setPadding(dp(20), dp(16), dp(20), dp(20)) }
        col.add(View(ctx), h = dp(56))
        col.add(Illustration(ctx, Art.LINK), h = dp(200))
        col.add(
            ctx.label("Bağlanıyor", 32f, C.TEXT, 700, display = true, spacing = -0.02f).apply { gravity = Gravity.CENTER },
            top = 12,
        )
        col.add(
            ctx.label(a.selected?.name ?: "", 16f, C.MUTED, 400).apply { gravity = Gravity.CENTER },
            top = 6,
        )
        stepsBox = ctx.column {
            background = ctx.rounded(C.SURFACE, 22, C.LINE, 1)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        col.add(stepsBox, top = 24)
        col.add(View(ctx), h = 0, weight = 1f)
        col.add(
            ctx.bigButton("Vazgeç", C.SURFACE2, C.TEXT) { a.stopMirror() },
            h = dp(60),
        )
        renderSteps()
        return col
    }

    fun setStage(n: Int) {
        if (n <= stage) return
        stage = n
        if (::stepsBox.isInitialized) renderSteps()
    }

    private fun renderSteps() {
        val ctx = a
        stepsBox.removeAllViews()
        steps.forEachIndexed { i, name ->
            val r = ctx.row { minimumHeight = dp(52) }
            val slot = FrameLayout(ctx)
            when {
                i < stage -> {
                    slot.background = ctx.circle(C.ACCENT)
                    slot.addView(ctx.icon(Icons.CHECK, C.ACCENT_DARK, 3f), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                }
                i == stage -> {
                    val pb = ProgressBar(ctx).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(C.ACCENT)
                    }
                    slot.addView(pb, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                }
                else -> slot.background = ctx.circle(0, C.LINE2, 2)
            }
            r.add(slot, w = dp(26), h = dp(26), end = 14)
            val color = if (i <= stage) C.TEXT else C.DIM
            r.add(ctx.label(name, 16f, color, if (i == stage) 700 else 500), w = 0, weight = 1f)
            stepsBox.add(r)
        }
    }

    override fun onBack() {
        a.stopMirror()
    }
}
