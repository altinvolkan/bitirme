package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.screenmirror.MainActivity
import com.example.screenmirror.monetization.Entitlements

class HomeScreen(a: MainActivity) : Screen(a) {

    private lateinit var sub: TextView
    private lateinit var list: LinearLayout
    private lateinit var latencyHolder: FrameLayout
    private lateinit var cta: LinearLayout
    private lateinit var goldLink: TextView
    private lateinit var bannerSlot: FrameLayout

    override fun build(): View {
        val ctx = a
        val outer = ctx.column {}
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = ctx.column { setPadding(dp(20), dp(16), dp(20), dp(12)) }

        // başlık satırı
        val header = ctx.row {}
        val dot = View(ctx).apply { background = ctx.circle(C.ACCENT) }
        header.add(dot, w = dp(10), h = dp(10), end = 8)
        header.add(ctx.label(str(R.string.app_name), 24f, C.TEXT, 700, display = true, spacing = -0.02f), WRAP, WRAP)
        header.spacer()
        header.add(ctx.iconButton(Icons.SLIDERS, str(R.string.cd_settings)) { a.openSettings() }, w = dp(44), h = dp(44))
        col.add(header, h = dp(56))

        col.add(ctx.label(str(R.string.home_title), 32f, C.TEXT, 700, display = true, spacing = -0.02f), top = 20)
        sub = ctx.label("", 16f, C.MUTED, 400)
        col.add(sub, top = 6)

        list = ctx.column {}
        col.add(list, top = 18)

        col.add(
            ctx.label(str(R.string.home_manual), 15f, C.ACCENT, 600).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { a.openNoDevice() }
            },
            h = dp(48), top = 6,
        )

        latencyHolder = FrameLayout(ctx)
        col.add(latencyHolder, top = 8)

        col.add(View(ctx), h = 0, weight = 1f)

        cta = ctx.bigButton(str(R.string.home_start), C.ACCENT, C.ACCENT_DARK, Icons.PLAY) { a.startMirror() }
        col.add(cta, h = dp(60), top = 20)
        col.add(
            ctx.label(str(R.string.home_start_hint), 13f, C.DIM, 400).apply { gravity = Gravity.CENTER },
            top = 10,
        )
        goldLink = ctx.label("", 15f, C.GOLD, 600).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { a.openPremium() }
        }
        col.add(goldLink, h = dp(48), top = 2)

        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        outer.add(scroll, h = 0, weight = 1f)

        bannerSlot = FrameLayout(ctx)
        outer.add(bannerSlot, h = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        refresh()
        return outer
    }

    override fun onShown() {
        refresh()
    }

    override fun onHidden() {
        a.ads.detachBanner()
    }

    override fun onPremiumChanged() = refresh()
    override fun onDevicesChanged() = refresh()

    override fun onBack() {
        a.finishApp()
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        val ctx = a
        val devices = a.devices
        sub.text = when (devices.size) {
            0 -> str(R.string.home_searching)
            else -> a.resources.getQuantityString(R.plurals.home_found, devices.size, devices.size)
        }

        list.removeAllViews()
        devices.forEach { d -> list.add(deviceCard(d), top = 10) }
        if (devices.isEmpty()) list.add(searchingCard(), top = 10)

        latencyHolder.removeAllViews()
        if (a.selected?.kind == Kind.AIRPLAY || a.selected?.kind == Kind.CAST) latencyHolder.addView(latencyCard(), FrameLayout.LayoutParams(MATCH, WRAP))

        val canStart = a.selected != null
        cta.alpha = if (canStart) 1f else 0.4f

        if (Entitlements.premium) {
            goldLink.visibility = View.GONE
            bannerSlot.visibility = View.GONE
            a.ads.detachBanner()
        } else {
            goldLink.visibility = View.VISIBLE
            val price = a.billing.priceText ?: "…"
            goldLink.text = str(R.string.home_remove_ads, str(R.string.price_per_month, price))
            bannerSlot.visibility = View.VISIBLE
            bannerSlot.minimumHeight = dp(50)
            a.ads.showBanner(bannerSlot)
        }
    }

    private fun searchingCard(): View {
        val ctx = a
        return ctx.row {
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = ctx.rounded(C.SURFACE, 22, C.LINE, 1)
            add(ctx.icon(Icons.WIFI, C.DIM), w = dp(24), h = dp(24), end = 12)
            add(ctx.label(str(R.string.home_searching_card), 16f, C.MUTED, 500), w = 0, weight = 1f)
        }
    }

    private fun deviceCard(d: Device): View {
        val ctx = a
        val on = a.selected?.key == d.key
        return ctx.row {
            setPadding(dp(14), dp(14), dp(16), dp(14))
            minimumHeight = dp(76)
            isClickable = true
            background = if (on) ctx.rippleRounded(C.ACCENT_TINT, 22, C.ACCENT, 2) else ctx.rippleRounded(C.SURFACE, 22, C.LINE, 1)
            val badge = FrameLayout(ctx).apply { background = ctx.rounded(C.SURFACE2, 14) }
            badge.addView(ctx.icon(Icons.TV, if (on) C.ACCENT else C.TEXT), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            add(badge, w = dp(48), h = dp(48), end = 14)
            val texts = ctx.column {}
            texts.add(ctx.label(d.name, 18f, C.TEXT, 700, display = true))
            texts.add(ctx.label(d.subtitle, 14f, C.MUTED, 400), top = 2)
            add(texts, w = 0, weight = 1f)
            if (on) {
                val check = FrameLayout(ctx).apply { background = ctx.circle(C.ACCENT) }
                check.addView(ctx.icon(Icons.CHECK, C.ACCENT_DARK, 3f), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                add(check, w = dp(28), h = dp(28))
            } else {
                add(View(ctx).apply { background = ctx.circle(0, C.LINE2, 2) }, w = dp(28), h = dp(28))
            }
            setOnClickListener {
                a.select(d)
                refresh()
            }
        }
    }

    private fun latencyCard(): View {
        val ctx = a
        val card = ctx.column {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ctx.rounded(C.SURFACE, 22, C.LINE, 1)
        }
        card.add(ctx.label(str(R.string.latency_title), 14f, C.MUTED, 600), start = 2)
        val seg = ctx.row {
            background = ctx.rounded(C.BG, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        UiPrefs.LATENCIES.forEachIndexed { i, (name, ms) ->
            val on = i == a.prefs.latencyIdx
            val cell = ctx.column {
                gravity = Gravity.CENTER
                isClickable = true
                background = if (on) ctx.rounded(C.ACCENT, 11) else ctx.rippleRounded(0, 11)
                add(ctx.label(str(name), 15f, if (on) C.ACCENT_DARK else C.TEXT, 700).apply { gravity = Gravity.CENTER })
                add(ctx.label("$ms ms", 12f, if (on) C.ACCENT_DARK else C.DIM, 500).apply { gravity = Gravity.CENTER })
                setOnClickListener {
                    a.prefs.latencyIdx = i
                    refresh()
                }
            }
            seg.add(cell, w = 0, h = dp(52), weight = 1f)
        }
        card.add(seg, top = 10)
        card.add(ctx.label(str(R.string.latency_hint), 12f, C.DIM, 400), top = 8, start = 2)
        return card
    }
}
