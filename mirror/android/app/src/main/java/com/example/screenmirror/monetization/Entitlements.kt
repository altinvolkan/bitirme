package com.example.screenmirror.monetization

import android.content.Context

/**
 * Reklamsız (premium) abonelik durumu. Play Billing'den gelen son bilinen değer
 * cihazda önbelleğe alınır; böylece çevrimdışıyken de premium kullanıcı reklam görmez.
 * Değer yalnızca Billing başarılı yanıt verdiğinde değiştirilir (hata anında hak silinmez).
 *
 * Ayrıca: Play'in yerelleştirilmiş fiyat metni (servis/filigran de okuyabilsin diye saklanır) ve
 * ödüllü reklamla kazanılan "filigransız" sürenin bitiş zamanı.
 */
object Entitlements {
    private const val PREFS = "entitlements"
    private const val KEY_PREMIUM = "premium"
    private const val KEY_PRICE = "price"
    private const val KEY_REWARD_UNTIL = "reward_until"

    @Volatile
    var premium: Boolean = false
        private set

    /** Play'den gelen son fiyat metni (ör. "₺99,00"); henüz görülmediyse boş. */
    @Volatile
    var priceText: String = ""
        private set

    @Volatile
    private var rewardUntilMs: Long = 0L

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        premium = sp.getBoolean(KEY_PREMIUM, false)
        priceText = sp.getString(KEY_PRICE, "") ?: ""
        rewardUntilMs = sp.getLong(KEY_REWARD_UNTIL, 0L)
    }

    fun set(ctx: Context, value: Boolean) {
        premium = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PREMIUM, value).apply()
    }

    fun setPrice(ctx: Context, text: String) {
        priceText = text
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PRICE, text).apply()
    }

    /** Ödüllü reklam izlendi: [minutes] dakika filigran gösterme. */
    fun grantReward(ctx: Context, minutes: Int) {
        rewardUntilMs = System.currentTimeMillis() + minutes * 60_000L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_REWARD_UNTIL, rewardUntilMs).apply()
    }

    val rewardActive: Boolean get() = System.currentTimeMillis() < rewardUntilMs

    /** Filigran gösterilmemeli mi (premium veya ödüllü süre içinde)? */
    val watermarkFree: Boolean get() = premium || rewardActive
}
