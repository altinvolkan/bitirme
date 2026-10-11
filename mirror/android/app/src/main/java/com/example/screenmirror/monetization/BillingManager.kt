package com.example.screenmirror.monetization

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing ile "Reklamsız" aboneliği (ayda 99 TL).
 *
 * Play Console'da: Abonelikler -> ürün kimliği [PRODUCT_ID], tek bir temel plan
 * (aylık, otomatik yenilenen), fiyat 99 TRY. Kod fiyatı Play'den okur; sabit yazılmaz.
 */
class BillingManager(
    private val context: Context,
    /** Durum/fiyat değişti; main thread'de çağrılır. */
    private val onChanged: () -> Unit,
) : PurchasesUpdatedListener {

    private val main = Handler(Looper.getMainLooper())
    private var details: ProductDetails? = null

    /** Play'den gelen yerelleştirilmiş fiyat metni (ör. "₺99,00"); henüz yoksa null. */
    @Volatile
    var priceText: String? = null
        private set

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun start() = withConnection {
        queryDetails()
        refresh()
    }

    /** Aktif abonelikleri yeniden sorgular (uygulama öne gelince ve "geri yükle" için). */
    fun refresh() = withConnection {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases)
        }
    }

    fun launchPurchase(activity: Activity) {
        val d = details ?: return run { start() }
        val offer = d.subscriptionOfferDetails?.firstOrNull() ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(d)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    /** Aboneliği yönetme/iptal sayfası (Play Store). */
    fun manageSubscription(activity: Activity) {
        val uri = Uri.parse(
            "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=${activity.packageName}",
        )
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun end() {
        if (client.isReady) client.endConnection()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases)
        // USER_CANCELED ve diğerleri: durum değişmez
    }

    private fun handle(purchases: List<Purchase>?) {
        val active = purchases.orEmpty().filter {
            it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(PRODUCT_ID)
        }
        // Bekleyen/doğrulanmamış satın almayı 3 gün içinde onaylamazsanız Play iade eder.
        active.filterNot { it.isAcknowledged }.forEach { p ->
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build(),
            ) { }
        }
        Entitlements.set(context, active.isNotEmpty())
        main.post(onChanged)
    }

    private fun queryDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            details = list.firstOrNull()
            priceText = details?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            main.post(onChanged)
        }
    }

    private fun withConnection(block: () -> Unit) {
        if (client.isReady) return block()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) block()
            }

            override fun onBillingServiceDisconnected() {
                // Bir sonraki start()/refresh() çağrısında yeniden bağlanılır
            }
        })
    }

    companion object {
        const val PRODUCT_ID = "premium_monthly"
    }
}
