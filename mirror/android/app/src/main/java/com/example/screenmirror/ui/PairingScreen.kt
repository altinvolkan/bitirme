package com.example.screenmirror.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.screenmirror.MainActivity

/** Apple TV ekranındaki 4 haneli kodu girme ekranı. */
class PairingScreen(a: MainActivity, private val deviceName: String) : Screen(a) {

    private val digits = StringBuilder()
    private val boxes = ArrayList<FrameLayout>()
    private val boxTexts = ArrayList<TextView>()
    private lateinit var confirm: LinearLayout

    override fun build(): View {
        val ctx = a
        val col = ctx.column { setPadding(dp(20), dp(16), dp(20), dp(20)) }
        col.add(ctx.topBar(null, Icons.BACK, "Geri") { onBack() }, h = dp(56))

        col.add(ctx.label("Eşleştirme kodu", 32f, C.TEXT, 700, display = true, spacing = -0.02f), top = 16)
        col.add(
            ctx.label("$deviceName ekranında görünen 4 haneli kodu gir.", 16f, C.MUTED, 400).apply { setLineSpacing(0f, 1.15f) },
            top = 8,
        )

        val boxRow = ctx.row { gravity = Gravity.CENTER }
        boxes.clear()
        boxTexts.clear()
        repeat(4) {
            val box = FrameLayout(ctx)
            val t = ctx.label("", 30f, C.TEXT, 700, display = true).apply { gravity = Gravity.CENTER }
            box.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
            boxes.add(box)
            boxTexts.add(t)
            boxRow.add(box, w = 0, h = dp(68), weight = 1f, start = 5, end = 5)
        }
        col.add(boxRow, top = 28, start = 0, end = 0)

        col.add(View(ctx), h = 0, weight = 1f)

        // sayısal tuş takımı
        val pad = ctx.column {}
        val keys = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<"),
        )
        for (r in keys) {
            val line = ctx.row { }
            for (k in r) line.add(key(k), w = 0, h = dp(58), weight = 1f, start = 4, end = 4)
            pad.add(line, top = 8)
        }
        col.add(pad)

        confirm = ctx.bigButton("Eşleştir", C.ACCENT, C.ACCENT_DARK, Icons.CHECK, iconFilled = false) { submit() }
        col.add(confirm, h = dp(60), top = 18)
        update()
        return col
    }

    private fun key(k: String): View {
        val ctx = a
        if (k.isEmpty()) return View(ctx)
        val f = FrameLayout(ctx).apply {
            background = ctx.rippleRounded(C.SURFACE, 18, C.LINE, 1)
            isClickable = true
            setOnClickListener {
                if (k == "<") {
                    if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
                } else if (digits.length < 4) {
                    digits.append(k)
                }
                update()
            }
        }
        if (k == "<") {
            f.contentDescription = "Sil"
            f.addView(ctx.icon(Icons.BACKSPACE, C.TEXT), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        } else {
            f.addView(
                ctx.label(k, 24f, C.TEXT, 600, display = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
        }
        return f
    }

    private fun update() {
        for (i in 0 until 4) {
            val has = i < digits.length
            val active = i == digits.length
            boxTexts[i].text = if (has) digits[i].toString() else ""
            boxes[i].background = when {
                active -> a.rounded(C.SURFACE, 18, C.ACCENT, 2)
                has -> a.rounded(C.SURFACE2, 18, C.LINE2, 1)
                else -> a.rounded(C.SURFACE, 18, C.LINE, 1)
            }
        }
        confirm.alpha = if (digits.length == 4) 1f else 0.4f
    }

    private fun submit() {
        if (digits.length != 4) return
        a.submitPin(digits.toString())
    }

    override fun onBack() {
        a.cancelPin()
    }
}
