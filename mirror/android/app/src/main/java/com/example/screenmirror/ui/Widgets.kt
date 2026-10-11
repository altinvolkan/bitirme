package com.example.screenmirror.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Sol üstte geri/kapat düğmesi olan 56 dp'lik üst çubuk. */
fun Context.topBar(title: String?, iconPath: String, desc: String, onClick: () -> Unit): LinearLayout = row {
    add(iconButton(iconPath, desc, onClick), w = dp(44), h = dp(44))
    if (title != null) {
        add(label(title, 26f, C.TEXT, 700, display = true, spacing = -0.02f), WRAP, WRAP, start = 12)
    }
}

fun Context.divider(): View = View(this).apply { setBackgroundColor(C.LINE) }

/** Ayarlardaki gruplanmış kart. */
fun Context.groupCard(vararg rows: View): LinearLayout = column {
    background = rounded(C.SURFACE, 20, C.LINE, 1)
    rows.forEachIndexed { i, r ->
        if (i > 0) add(divider(), h = 1, start = 16, end = 16)
        add(r)
    }
}

fun Context.groupTitle(s: String): TextView = label(s, 13f, C.MUTED, 600)

/** Ayar satırı: ad + sağda değer + ok. */
fun Context.settingRow(title: String, value: String?, chevron: Boolean = true, titleColor: Int = C.TEXT, onClick: () -> Unit): LinearLayout = row {
    setPadding(dp(16), 0, dp(16), 0)
    minimumHeight = dp(60)
    isClickable = true
    background = rippleRounded(0, 20)
    add(label(title, 16f, titleColor, 500), w = 0, weight = 1f)
    if (value != null) add(label(value, 15f, C.MUTED, 400), WRAP, WRAP, end = if (chevron) 8 else 0)
    if (chevron) add(icon(Icons.CHEVRON, C.DIM, 2f), w = dp(18), h = dp(18))
    setOnClickListener { onClick() }
}
