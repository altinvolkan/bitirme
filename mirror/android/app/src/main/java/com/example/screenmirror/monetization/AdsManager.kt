package com.example.screenmirror.monetization

import android.app.Activity
import android.os.SystemClock
import android.view.ViewGroup
import com.example.screenmirror.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob: ana ekranda banner + yansıtmayı başlatmadan önce sınırlı sıklıkta geçiş reklamı.
 * Yansıtma sırasında reklam gösterilmez. Premium kullanıcıya hiç reklam yüklenmez.
 *
 * Kullanıcı onayı (GDPR/UMP) alınmadan reklam isteği yapılmaz. AdMob konsolunda
 * "Gizlilik ve mesajlaşma" bölümünden onay formu oluşturulmalıdır.
 */
class AdsManager(private val activity: Activity) {

    private val sdkStarted = AtomicBoolean(false)
    private var interstitial: InterstitialAd? = null
    private var lastInterstitialAt = 0L
    private var banner: AdView? = null

    /** Onay akışını çalıştırır; izin varsa SDK'yı başlatır. [ready] SDK hazır olunca çağrılır. */
    fun start(ready: () -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (info.canRequestAds()) startSdk(ready)
                }
            },
            { if (info.canRequestAds()) startSdk(ready) },
        )
        // Önceki oturumda onay alındıysa beklemeden başla
        if (info.canRequestAds()) startSdk(ready)
    }

    private fun startSdk(ready: () -> Unit) {
        if (!sdkStarted.compareAndSet(false, true)) return
        MobileAds.initialize(activity) {
            activity.runOnUiThread {
                preloadInterstitial()
                ready()
            }
        }
    }

    /** Banner'ı [container] içine koyar (premium ise hiçbir şey yapmaz). */
    fun showBanner(container: ViewGroup) {
        if (Entitlements.premium || !sdkStarted.get()) return
        banner?.let { existing ->
            existing.resume()
            (existing.parent as? ViewGroup)?.removeView(existing)
            container.removeAllViews()
            container.addView(existing)
            return
        }
        val view = AdView(activity).apply {
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            setAdSize(AdSize.BANNER)
            adListener = object : AdListener() {}
            loadAd(AdRequest.Builder().build())
        }
        banner = view
        container.addView(view)
    }

    /** Ekran değişirken banner'ı yok etmeden ayırır. */
    fun detachBanner() {
        banner?.let {
            it.pause() // görünmezken reklam yenileme ağ/CPU harcamasın
            (it.parent as? ViewGroup)?.removeView(it)
        }
    }

    fun hideBanner(container: ViewGroup) {
        banner?.destroy()
        container.removeAllViews()
        banner = null
    }

    private fun preloadInterstitial() {
        if (Entitlements.premium || interstitial != null) return
        InterstitialAd.load(
            activity, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null
                }
            },
        )
    }

    /**
     * Yansıtmayı başlatmadan önce çağrılır: uygunsa geçiş reklamı gösterir, bitince [action]'ı
     * çalıştırır. Premium, reklam hazır değil veya son reklamdan beri 3 dk geçmediyse doğrudan [action].
     */
    fun showInterstitialThen(action: () -> Unit) {
        val ad = interstitial
        val now = SystemClock.elapsedRealtime()
        if (Entitlements.premium || ad == null || now - lastInterstitialAt < COOLDOWN_MS) {
            return action()
        }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                lastInterstitialAt = SystemClock.elapsedRealtime()
                preloadInterstitial()
                action()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                preloadInterstitial()
                action()
            }
        }
        ad.show(activity)
    }

    fun destroy() {
        banner?.destroy()
        banner = null
    }

    companion object {
        private const val COOLDOWN_MS = 3 * 60 * 1000L
    }
}
