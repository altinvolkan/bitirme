package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import com.example.screenmirror.MainActivity

class NoDeviceScreen(a: MainActivity) : Screen(a) {

    private lateinit var ip: EditText

    override fun build(): View {
        val ctx = a
        val outer = ctx.column { setPadding(dp(20), dp(16), dp(20), dp(20)) }
        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val col = ctx.column {}

        val header = ctx.row {}
        header.add(ctx.iconButton(Icons.BACK, str(R.string.cd_back)) { onBack() }, w = dp(44), h = dp(44))
        header.spacer()
        header.add(ctx.iconButton(Icons.SLIDERS, str(R.string.cd_settings)) { a.openSettings() }, w = dp(44), h = dp(44))
        outer.add(header, h = dp(56))

        val badge = FrameLayout(ctx).apply { background = ctx.rounded(0, 16, C.LINE2, 2) }
        badge.addView(ctx.icon(Icons.HELP, C.DIM, 1.6f), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        col.add(badge, w = dp(132), h = dp(88), top = 20, gravity = Gravity.CENTER_HORIZONTAL)
        col.add(
            ctx.label(str(R.string.nodevice_title), 30f, C.TEXT, 700, display = true, spacing = -0.02f).apply { gravity = Gravity.CENTER },
            top = 22,
        )
        col.add(
            ctx.label(str(R.string.nodevice_body), 16f, C.MUTED, 400).apply { gravity = Gravity.CENTER },
            top = 8, start = 12, end = 12,
        )

        val tips = ctx.column {
            background = ctx.rounded(C.SURFACE, 22, C.LINE, 1)
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        listOf(
            str(R.string.tip1),
            str(R.string.tip2),
            str(R.string.tip3),
        ).forEachIndexed { i, s ->
            if (i > 0) tips.add(ctx.divider(), h = 1)
            val r = ctx.row { setPadding(0, dp(14), 0, dp(14)) }
            r.gravity = Gravity.TOP
            r.add(ctx.label("${i + 1}", 18f, C.ACCENT, 700, display = true), w = dp(18), end = 14)
            r.add(ctx.label(s, 16f, C.TEXT, 400).apply { setLineSpacing(0f, 1.1f) }, w = 0, weight = 1f)
            tips.add(r)
        }
        col.add(tips, top = 24)

        col.add(ctx.label(str(R.string.nodevice_ip_label), 14f, C.MUTED, 600), top = 16, start = 4)
        val ipRow = ctx.row {}
        ip = EditText(ctx).apply {
            hint = "192.168.1.118"
            setHintTextColor(C.DIM)
            setTextColor(C.TEXT)
            textSize = 17f
            typeface = Fonts.body(500)
            inputType = InputType.TYPE_CLASS_PHONE
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
            background = ctx.rounded(C.SURFACE, 16, C.LINE2, 1)
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, _, _ ->
                connect()
                true
            }
        }
        ipRow.add(ip, w = 0, h = dp(52), weight = 1f, end = 10)
        val go = ctx.label(str(R.string.nodevice_connect), 16f, C.TEXT, 600).apply {
            gravity = Gravity.CENTER
            background = ctx.rippleRounded(C.LINE, 16)
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            setOnClickListener { connect() }
        }
        ipRow.add(go, w = WRAP, h = dp(52))
        col.add(ipRow, top = 8)

        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        outer.add(scroll, h = 0, weight = 1f)
        outer.add(
            ctx.bigButton(str(R.string.nodevice_retry), C.ACCENT, C.ACCENT_DARK, Icons.REFRESH, iconFilled = false) { a.rescan() },
            h = dp(60), top = 12,
        )
        return outer
    }

    private fun connect() {
        a.connectManual(ip.text.toString().trim())
    }

    override fun onDevicesChanged() {
        // Aranırken bir ekran bulunduysa ana ekrana dön
        if (a.devices.isNotEmpty()) a.goHome()
    }
}
