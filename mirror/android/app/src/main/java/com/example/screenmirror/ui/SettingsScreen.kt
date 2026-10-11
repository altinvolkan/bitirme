package com.example.screenmirror.ui

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
        outer.add(ctx.topBar("Ayarlar", Icons.BACK, "Geri") { onBack() }, h = dp(56))

        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val col = ctx.column {}

        // Yayın
        col.add(ctx.groupTitle("Yayın"), top = 14, start = 4)
        val latency = UiPrefs.LATENCIES[a.prefs.latencyIdx]
        val rLatency = ctx.settingRow("Gecikme payı", "${latency.first} · ${latency.second} ms") {
            choose("Gecikme payı", UiPrefs.LATENCIES.map { "${it.first} · ${it.second} ms" }, a.prefs.latencyIdx) {
                a.prefs.latencyIdx = it
                rebuild()
            }
        }
        val rBitrate = ctx.settingRow("Görüntü kalitesi", "${a.prefs.bitrateMbps} Mbps") {
            choose("Görüntü kalitesi", UiPrefs.BITRATES.map { "$it Mbps" }, a.prefs.bitrateIdx) {
                a.prefs.bitrateIdx = it
                rebuild()
            }
        }
        val rAudio = ctx.row {
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(60)
            add(ctx.label("Sistem sesini gönder", 16f, C.TEXT, 500), w = 0, weight = 1f)
            add(Toggle(ctx, a.prefs.audio) { on ->
                a.prefs.audio = on
                MirrorService.instance?.setAudioMuted(!on)
            }, w = dp(52), h = dp(32))
        }
        col.add(ctx.groupCard(rLatency, rBitrate, rAudio), top = 8)
        col.add(
            ctx.label("Gecikme ve kalite, bir sonraki yansıtmada geçerli olur.", 12f, C.DIM, 400),
            top = 6, start = 4,
        )

        // Cihazlar
        col.add(ctx.groupTitle("Cihazlar"), top = 20, start = 4)
        val n = AirPlayCredentials.count(a)
        val rPaired = ctx.settingRow("Eşleşmiş ekranlar", "$n cihaz", chevron = false) {}
        val rReset = ctx.settingRow("Tüm eşleşmeleri sıfırla", null, chevron = false, titleColor = C.LIVE_TEXT) {
            AlertDialog.Builder(a)
                .setTitle("Eşleşmeler sıfırlansın mı?")
                .setMessage("Bir sonraki bağlantıda Apple TV'deki kodu yeniden girmen gerekir.")
                .setPositiveButton("Sıfırla") { _, _ ->
                    AirPlayCredentials.clear(a)
                    a.toast("Eşleşmeler silindi")
                    rebuild()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        col.add(ctx.groupCard(rPaired, rReset), top = 8)

        // Hesap
        col.add(ctx.groupTitle("Hesap"), top = 20, start = 4)
        val rPremium = ctx.row {
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(60)
            isClickable = true
            background = ctx.rippleRounded(0, 20)
            add(
                ctx.label(if (Entitlements.premium) "Premium aktif (aboneliği yönet)" else "Reklamsız kullan", 16f, C.TEXT, 500),
                w = 0, weight = 1f,
            )
            if (!Entitlements.premium) {
                val price = a.billing.priceText ?: "₺99"
                add(
                    ctx.label("$price/ay", 14f, C.GOLD_DARK, 700).apply {
                        background = ctx.rounded(C.GOLD, 12)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                    },
                    WRAP, WRAP,
                )
            }
            setOnClickListener { a.openPremium() }
        }
        val rRestore = ctx.settingRow("Satın alımı geri yükle", null, chevron = false) {
            a.billing.refresh()
            a.toast("Satın alımlar kontrol ediliyor…")
        }
        val rTour = ctx.settingRow("Kullanım ipuçları", null) { a.openOnboarding() }
        col.add(ctx.groupCard(rPremium, rRestore, rTour), top = 8)

        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        outer.add(scroll, h = 0, weight = 1f)

        val footer = ctx.row {}
        footer.add(ctx.label("Sürüm ${a.versionName()}", 13f, C.DIM, 400), w = 0, weight = 1f)
        footer.add(
            ctx.label("Gizlilik politikası", 13f, C.MUTED, 400).apply {
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
