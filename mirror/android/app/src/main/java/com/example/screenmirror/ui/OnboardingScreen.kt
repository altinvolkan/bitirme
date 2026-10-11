package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.screenmirror.MainActivity

/** İlk açılışta (ve ayarlardan) gösterilen ipucu turu. */
class OnboardingScreen(a: MainActivity, private val fromSettings: Boolean = false) : Screen(a) {

    private class Step(val art: Art, val tint: Int, val title: String, val body: String)

    private val steps = listOf(
        Step(
            Art.WIFI, C.ACCENT, str(R.string.onb1_title), str(R.string.onb1_body),
        ),
        Step(
            Art.FIVE_G, C.ACCENT, str(R.string.onb2_title), str(R.string.onb2_body),
        ),
        Step(
            Art.NEAR, C.ACCENT, str(R.string.onb3_title), str(R.string.onb3_body),
        ),
        Step(
            Art.ETHERNET, C.ACCENT, str(R.string.onb4_title), str(R.string.onb4_body),
        ),
        Step(
            Art.SCOPE, C.ACCENT, str(R.string.onb5_title), str(R.string.onb5_body, str(R.string.scope_entire), str(R.string.scope_single)),
        ),
        Step(
            Art.DRM, C.LIVE_TEXT, str(R.string.onb6_title), str(R.string.onb6_body),
        ),
    )

    private var index = 0
    private lateinit var content: FrameLayout

    override fun build(): View {
        val ctx = a
        val root = ctx.column { setPadding(dp(20), dp(8), dp(20), dp(20)) }

        // üst çubuk: sağda "tanıtımı atla"
        val top = ctx.row { }
        top.spacer()
        val skip = ctx.label(str(R.string.onb_skip), 15f, C.MUTED, 600).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            setOnClickListener { finish() }
        }
        top.add(skip, w = WRAP, h = dp(48))
        root.add(top, h = dp(56))

        content = FrameLayout(ctx)
        root.add(content, h = 0, weight = 1f)
        render()
        return root
    }

    private fun render() {
        val ctx = a
        content.removeAllViews()
        val step = steps[index]
        val last = index == steps.lastIndex

        val col = ctx.column { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(Illustration(ctx, step.art), h = 0, weight = 1.15f)
        col.add(
            ctx.label(step.title, 28f, C.TEXT, 700, display = true, spacing = -0.02f).apply {
                gravity = Gravity.CENTER
            },
            top = 8,
        )
        col.add(
            ctx.label(step.body, 16f, C.MUTED, 400).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            },
            top = 12, start = 4, end = 4,
        )
        col.add(View(ctx), h = 0, weight = 0.35f)

        // sayfa noktaları
        val dots = ctx.row { gravity = Gravity.CENTER }
        for (i in steps.indices) {
            val on = i == index
            val d = View(ctx).apply { background = ctx.rounded(if (on) step.tint else C.LINE2, 4) }
            dots.add(d, w = dp(if (on) 22 else 8), h = dp(8), start = 4, end = 4)
        }
        col.add(dots, top = 4, bottom = 20)

        val btn = if (last) {
            ctx.bigButton(str(R.string.onb_start), C.ACCENT, C.ACCENT_DARK, Icons.PLAY) { finish() }
        } else {
            ctx.bigButton(str(R.string.onb_next), C.SURFACE2, C.TEXT, Icons.CHEVRON, iconFilled = false) {
                index++
                render()
            }
        }
        col.add(btn, h = dp(60))
        content.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun finish() {
        a.prefs.onboarded = true
        if (fromSettings) a.back() else a.goHome()
    }

    override fun onBack() {
        if (index > 0) {
            index--
            render()
        } else if (fromSettings) {
            a.back()
        } else {
            a.finishApp()
        }
    }
}
