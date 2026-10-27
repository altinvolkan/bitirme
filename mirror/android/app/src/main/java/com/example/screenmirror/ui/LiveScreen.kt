package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.screenmirror.LiveInfo
import com.example.screenmirror.MainActivity
import com.example.screenmirror.MirrorService
import com.example.screenmirror.monetization.Entitlements

class LiveScreen(a: MainActivity) : Screen(a) {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var elapsed: TextView
    private lateinit var resV: TextView
    private lateinit var fpsV: TextView
    private lateinit var bitV: TextView
    private lateinit var latV: TextView
    private lateinit var bannerSlot: FrameLayout
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun build(): View {
        val ctx = a
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = ctx.column { setPadding(dp(20), dp(16), dp(20), dp(20)) }

        val top = ctx.row {}
        val pill = ctx.row {
            gravity = Gravity.CENTER
            background = ctx.rounded(C.LIVE_TINT, 22)
            setPadding(dp(16), 0, dp(18), 0)
        }
        pill.add(View(ctx).apply { background = ctx.circle(C.LIVE) }, w = dp(10), h = dp(10), end = 8)
        pill.add(ctx.label(str(R.string.live_badge), 16f, C.LIVE_TEXT, 700), WRAP, WRAP)
        top.add(pill, w = WRAP, h = dp(44))
        top.spacer()
        top.add(ctx.iconButton(Icons.SLIDERS, str(R.string.cd_settings)) { a.openSettings() }, w = dp(44), h = dp(44))
        col.add(top, h = dp(56))

        col.add(
            ctx.label(LiveInfo.deviceName.ifEmpty { a.selected?.name ?: "" }, 30f, C.TEXT, 700, display = true, spacing = -0.02f),
            top = 16,
        )
        elapsed = ctx.label("", 16f, C.MUTED, 400)
        col.add(elapsed, top = 4)

        col.add(Illustration(ctx, Art.LINK, animate = false), h = dp(150), top = 8)

        val grid = ctx.column {}
        resV = ctx.label("", 22f, C.TEXT, 700, display = true)
        fpsV = ctx.label("", 22f, C.TEXT, 700, display = true)
        bitV = ctx.label("", 22f, C.TEXT, 700, display = true)
        latV = ctx.label("", 22f, C.TEXT, 700, display = true)
        val r1 = ctx.row {}
        r1.add(stat(str(R.string.stat_resolution), resV), w = 0, weight = 1f, end = 6)
        r1.add(stat(str(R.string.stat_fps), fpsV), w = 0, weight = 1f, start = 6)
        val r2 = ctx.row {}
        r2.add(stat(str(R.string.stat_bitrate), bitV), w = 0, weight = 1f, end = 6)
        r2.add(stat(str(R.string.latency_title), latV), w = 0, weight = 1f, start = 6)
        grid.add(r1)
        grid.add(r2, top = 12)
        col.add(grid, top = 12)

        col.add(audioCard(), top = 12)
        if (!Entitlements.premium) col.add(premiumCard(), top = 12)
        col.add(
            ctx.label(str(R.string.live_rotate_hint), 13f, C.DIM, 400).apply { gravity = Gravity.CENTER },
            top = 14,
        )
        col.add(View(ctx), h = dp(16), weight = 1f)
        col.add(
            ctx.bigButton(str(R.string.live_stop), C.LIVE, C.LIVE_DARK, Icons.STOP) { a.stopMirror() },
            h = dp(60), top = 8,
        )
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        val outer = ctx.column {}
        outer.add(scroll, h = 0, weight = 1f)
        // Banner "Durdur" düğmesinden uzakta, en altta (yanlışlıkla tıklamayı önlemek için)
        bannerSlot = FrameLayout(ctx)
        outer.add(bannerSlot, h = WRAP, top = 8, gravity = Gravity.CENTER_HORIZONTAL)
        update()
        return outer
    }

    private fun stat(name: String, value: TextView): View {
        val ctx = a
        return ctx.column {
            background = ctx.rounded(C.SURFACE, 20, C.LINE, 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            add(ctx.label(name, 13f, C.MUTED, 600))
            add(value, top = 4)
        }
    }

    /** Ücretsiz sürümde TV'de filigran görünür; buradan Premium'a yönlendir. */
    private fun premiumCard(): View {
        val ctx = a
        return ctx.row {
            background = ctx.rippleRounded(C.GOLD_TINT, 20, C.GOLD, 1)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            isClickable = true
            val t = ctx.column {}
            t.add(ctx.label(str(R.string.wm_card_title), 16f, C.GOLD, 700))
            t.add(ctx.label(str(R.string.wm_card_body), 13f, C.MUTED, 400), top = 2)
            t.add(
                ctx.label(str(R.string.wm_reward), 13f, C.ACCENT, 600).apply {
                    isClickable = true
                    setPadding(0, dp(8), 0, dp(4))
                    setOnClickListener { watchRewarded() }
                },
            )
            add(t, w = 0, weight = 1f)
            add(ctx.icon(Icons.CHEVRON, C.GOLD, 2f), w = dp(20), h = dp(20))
            setOnClickListener { a.openPremium() }
        }
    }

    /** İsteğe bağlı ödüllü reklam: izlenirse 30 dk filigran yok. */
    private fun watchRewarded() {
        val shown = a.ads.showRewarded {
            Entitlements.grantReward(a, 30)
            a.toast(str(R.string.wm_reward_ok))
        }
        if (!shown) a.toast(str(R.string.wm_reward_wait))
    }

    private fun audioCard(): View {
        val ctx = a
        return ctx.row {
            background = ctx.rounded(C.SURFACE, 20, C.LINE, 1)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(64)
            add(ctx.icon(Icons.VOLUME, C.ACCENT, 1.8f), w = dp(24), h = dp(24), end = 12)
            val t = ctx.column {}
            t.add(ctx.label(str(R.string.audio_title), 16f, C.TEXT, 600))
            t.add(ctx.label(str(R.string.audio_sub), 13f, C.MUTED, 400), top = 2)
            add(t, w = 0, weight = 1f)
            add(Toggle(ctx, a.prefs.audio) { on ->
                a.prefs.audio = on
                MirrorService.instance?.setAudioMuted(!on)
            }, w = dp(52), h = dp(32))
        }
    }

    private fun update() {
        val ctx = a
        val started = LiveInfo.startedAtMs
        val secs = if (started > 0) ((SystemClock.elapsedRealtime() - started) / 1000).toInt() else 0
        val time = if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
        else "%02d:%02d".format(secs / 60, secs % 60)
        elapsed.text = str(R.string.live_elapsed, time)
        resV.text = if (LiveInfo.width > 0) "${LiveInfo.width}×${LiveInfo.height}" else "—"
        fpsV.text = if (LiveInfo.fps > 0) "${LiveInfo.fps} fps" else "—"
        bitV.text = if (LiveInfo.bitrateMbps > 0) "${LiveInfo.bitrateMbps} Mbps" else "—"
        latV.text = if (LiveInfo.latencyMs > 0) "${LiveInfo.latencyMs} ms" else "—"
    }

    override fun onShown() {
        handler.removeCallbacks(tick)
        handler.post(tick)
        showBanner()
    }

    override fun onHidden() {
        handler.removeCallbacks(tick)
        a.ads.detachBanner()
    }

    override fun onPremiumChanged() = showBanner()

    private fun showBanner() {
        if (!::bannerSlot.isInitialized) return
        if (Entitlements.premium) {
            bannerSlot.visibility = View.GONE
            a.ads.detachBanner()
        } else {
            bannerSlot.visibility = View.VISIBLE
            bannerSlot.minimumHeight = dp(50)
            a.ads.showBanner(bannerSlot)
        }
    }

    override fun onBack() {
        // Yansıtma sürerken geri tuşu uygulamayı arka plana alır; durdurmak için düğme var
        a.moveTaskToBack(true)
    }
}
