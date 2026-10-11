package com.example.screenmirror.monetization

import android.content.Context

/**
 * Reklamsız (premium) abonelik durumu. Play Billing'den gelen son bilinen değer
 * cihazda önbelleğe alınır; böylece çevrimdışıyken de premium kullanıcı reklam görmez.
 * Değer yalnızca Billing başarılı yanıt verdiğinde değiştirilir (hata anında hak silinmez).
 */
object Entitlements {
    private const val PREFS = "entitlements"
    private const val KEY_PREMIUM = "premium"

    @Volatile
    var premium: Boolean = false
        private set

    fun load(ctx: Context) {
        premium = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREMIUM, false)
    }

    fun set(ctx: Context, value: Boolean) {
        premium = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PREMIUM, value).apply()
    }
}
