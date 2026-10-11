package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import com.example.screenmirror.AirPlayCredentials
import com.example.screenmirror.MainActivity
import com.example.screenmirror.MirrorService
import com.example.screenmirror.monetization.Entitlements

class SettingsScreen(a: MainActivity) : Screen(a) {

    override fun build(): View {
        val ctx = a
        val outer = ctx.column { setPadding(dp(20), dp(16), dp(20), dp(12)) }
        outer.add(ctx.topBar(str(R.string.cd_settings), Icons.BACK, str(R.string.cd_back)) { onBack() }, h = dp(56))

        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val col = ctx.column {}

        // Yayın
        col.add(ctx.groupTitle(str(R.string.set_group_stream)), top = 14, start = 4)
        val latency = UiPrefs.LATENCIES[a.prefs.latencyIdx]
        val rLatency = ctx.settingRow(str(R.string.latency_title), "${str(latency.first)} · ${latency.second} ms") {
            choose(str(R.string.latency_title), UiPrefs.LATENCIES.map { "${str(it.first)} · ${it.second} ms" }, a.prefs.latencyIdx) {
                a.prefs.latencyIdx = it
                rebuild()
            }
        }
        val rBitrate = ctx.settingRow(str(R.string.set_quality), "${a.prefs.bitrateMbps} Mbps") {
            choose(str(R.string.set_quality), UiPrefs.BITRATES.map { "$it Mbps" }, a.prefs.bitrateIdx) {
                a.prefs.bitrateIdx = it
                rebuild()
            }
        }
        val rAudio = ctx.row {
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(60)
            add(ctx.label(str(R.string.set_audio), 16f, C.TEXT, 500), w = 0, weight = 1f)
            add(Toggle(ctx, a.prefs.audio) { on ->
                a.prefs.audio = on
                MirrorService.instance?.setAudioMuted(!on)
            }, w = dp(52), h = dp(32))
        }
        col.add(ctx.groupCard(rLatency, rBitrate, rAudio), top = 8)
        col.add(
            ctx.label(str(R.string.set_apply_hint), 12f, C.DIM, 400),
            top = 6, start = 4,
        )

        // Cihazlar
        col.add(ctx.groupTitle(str(R.string.set_group_devices)), top = 20, start = 4)
        val n = AirPlayCredentials.count(a)
        val rPaired = ctx.settingRow(str(R.string.set_paired), a.resources.getQuantityString(R.plurals.devices_count, n, n), chevron = false) {}
        val rReset = ctx.settingRow(str(R.string.set_reset), null, chevron = false, titleColor = C.LIVE_TEXT) {
            AlertDialog.Builder(a)
                .setTitle(str(R.string.reset_title))
                .setMessage(str(R.string.reset_msg))
                .setPositiveButton(str(R.string.reset_ok)) { _, _ ->
                    AirPlayCredentials.clear(a)
                    a.toast(str(R.string.reset_done))
                    rebuild()
                }
                .setNegativeButton(str(R.string.action_cancel), null)
                .show()
        }
        col.add(ctx.groupCard(rPaired, rReset), top = 8)

        // Hesap
        col.add(ctx.groupTitle(str(R.string.set_group_account)), top = 20, start = 4)
        val rPremium = ctx.row {
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(60)
            isClickable = true
            background = ctx.rippleRounded(0, 20)
            add(
                ctx.label(if (Entitlements.premium) str(R.string.set_premium_active) else str(R.string.set_remove_ads), 16f, C.TEXT, 500),
                w = 0, weight = 1f,
            )
            if (!Entitlements.premium) {
                val price = a.billing.priceText ?: "…"
                add(
                    ctx.label(str(R.string.price_per_month, price), 14f, C.GOLD_DARK, 700).apply {
                        background = ctx.rounded(C.GOLD, 12)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                    },
                    WRAP, WRAP,
                )
            }
            setOnClickListener { a.openPremium() }
        }
        val rRestore = ctx.settingRow(str(R.string.restore), null, chevron = false) {
            a.billing.refresh()
            a.toast(str(R.string.restore_checking))
        }
        val rTour = ctx.settingRow(str(R.string.set_tour), null) { a.openOnboarding() }
        col.add(ctx.groupCard(rPremium, rRestore, rTour), top = 8)

        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        outer.add(scroll, h = 0, weight = 1f)

        val footer = ctx.row {}
        footer.add(ctx.label(str(R.string.set_version, a.versionName()), 13f, C.DIM, 400), w = 0, weight = 1f)
        footer.add(
            ctx.label(str(R.string.set_privacy), 13f, C.MUTED, 400).apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { a.openPrivacy() }
            },
            WRAP, dp(44),
        )
        outer.add(footer, top = 4, start = 4, end = 4)
        return outer
    }

    private fun rebuild() {
        a.show(SettingsScreen(a), push = false)
    }

    private fun choose(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(a)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .show()
    }

    override fun onPremiumChanged() = rebuild()
}
