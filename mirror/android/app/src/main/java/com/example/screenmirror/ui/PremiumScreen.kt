package com.example.screenmirror.ui

import com.example.screenmirror.R
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import com.example.screenmirror.MainActivity
import com.example.screenmirror.monetization.Entitlements

class PremiumScreen(a: MainActivity) : Screen(a) {

    override fun build(): View {
        val ctx = a
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = ctx.column { setPadding(dp(20), dp(16), dp(20), dp(20)) }
        col.add(ctx.topBar(null, Icons.CLOSE, str(R.string.cd_close)) { onBack() }, h = dp(56))

        col.add(ctx.label("Premium", 15f, C.GOLD, 600), top = 24)
        col.add(ctx.label(str(R.string.premium_title), 40f, C.TEXT, 800, display = true, spacing = -0.03f), top = 8)
        col.add(
            ctx.label(str(R.string.premium_body), 17f, C.MUTED, 400)
                .apply { setLineSpacing(0f, 1.15f) },
            top = 12,
        )

        val priceRow = ctx.row { gravity = Gravity.BOTTOM }
        val price = a.billing.priceText ?: "…"
        priceRow.add(ctx.label(price, 60f, C.GOLD, 800, display = true, spacing = -0.04f), WRAP, WRAP, end = 8)
        priceRow.add(ctx.label(str(R.string.premium_per_month), 18f, C.MUTED, 500), WRAP, WRAP, bottom = 10)
        col.add(priceRow, top = 28)

        listOf(
            str(R.string.premium_b1),
            str(R.string.premium_b2),
            str(R.string.premium_b3),
        ).forEach { s ->
            val r = ctx.row {}
            val badge = FrameLayout(ctx).apply { background = ctx.circle(C.GOLD_TINT) }
            badge.addView(ctx.icon(Icons.CHECK, C.GOLD, 3f), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            r.add(badge, w = dp(28), h = dp(28), end = 14)
            r.add(ctx.label(s, 17f, C.TEXT, 500), w = 0, weight = 1f)
            col.add(r, top = 16)
        }

        col.add(View(ctx), h = dp(24), weight = 1f)

        val premium = Entitlements.premium
        col.add(
            ctx.bigButton(
                if (premium) str(R.string.premium_manage) else str(R.string.premium_cta, a.billing.priceText ?: "…"),
                C.GOLD, C.GOLD_DARK,
            ) {
                if (premium) a.billing.manageSubscription(a) else a.billing.launchPurchase(a)
            },
            h = dp(60),
        )
        col.add(
            ctx.label(str(R.string.restore), 15f, C.MUTED, 600).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener {
                    a.billing.refresh()
                    a.toast(str(R.string.restore_checking))
                }
            },
            h = dp(48), top = 6,
        )
        col.add(
            ctx.label(
                str(R.string.premium_fine),
                12f, C.DIM, 400,
            ).apply { gravity = Gravity.CENTER },
            top = 4, start = 4, end = 4,
        )
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return scroll
    }

    override fun onPremiumChanged() {
        a.show(PremiumScreen(a), push = false)
    }
}
