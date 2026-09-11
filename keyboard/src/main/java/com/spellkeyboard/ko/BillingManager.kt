package com.spellkeyboard.ko

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play 구독.
 *
 * 여기서 하는 일은 넷이다 — 연결, 결제창 띄우기, 구매 확인(acknowledge), 구매 토큰 저장.
 * **"구독자인가" 는 여기서 판단하지 않는다.** 폰은 얼마든지 거짓말할 수 있어서, 서버가
 * 저장된 토큰을 Play 에 물어보고 결정한다. 여기서는 그 토큰을 [Prefs] 에 넣어 둘 뿐이다.
 *
 * 사이드로드한 APK 에서는 Play 결제가 동작하지 않는다. Play 스토어(내부 테스트라도)에서
 * 설치한 빌드여야 결제창이 뜬다. 그 전에는 [onStatus] 로 "결제를 쓸 수 없다" 가 온다.
 */
class BillingManager(
    context: Context,
    /** 사람에게 보여줄 한 줄. 빈 문자열이면 "구독 아님" 으로 바뀌었다는 뜻이다. */
    private val onStatus: (String) -> Unit
) : PurchasesUpdatedListener {

    private val app = context.applicationContext

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** 연결하고, 이미 구독 중이면(재설치·다른 기기) 토큰을 되찾는다. */
    fun start() {
        connect { restore() }
    }

    /** 결제창을 띄운다. 끝나면 [onPurchasesUpdated] 로 온다. */
    fun subscribe(activity: Activity) {
        connect { launch(activity) }
    }

    fun destroy() {
        runCatching { client.endConnection() }
    }

    private fun connect(then: () -> Unit) {
        if (client.isReady) {
            then()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    then()
                } else {
                    onStatus(describe(result))
                }
            }

            override fun onBillingServiceDisconnected() {
                // 다음 호출에서 다시 붙는다. 여기서 되풀이하면 끝없이 돈다.
            }
        })
    }

    private fun restore() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.forEach { handle(it) }
            // 살아 있는 구독이 하나도 없으면 옛 토큰을 버린다. 해지한 사람이 토큰을 계속
            // 보내 봐야 서버가 거절하지만, 안 보내는 편이 깔끔하다.
            if (purchases.none { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                Prefs.setPurchaseToken(app, "")
                onStatus("")
            }
        }
    }

    private fun launch(activity: Activity) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()

        client.queryProductDetailsAsync(params) { result, details ->
            val detail = details.firstOrNull()
            val offerToken = detail?.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (result.responseCode != BillingClient.BillingResponseCode.OK || detail == null || offerToken == null) {
                onStatus(app.getString(R.string.billing_product_missing))
                return@queryProductDetailsAsync
            }
            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(detail)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                .build()
            client.launchBillingFlow(activity, flow)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handle(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> onStatus(describe(result))
        }
    }

    /**
     * 구매를 받아들인다.
     *
     * 사흘 안에 acknowledge 하지 않으면 Play 가 환불해 버린다. 그래서 토큰을 저장하기
     * 전에 먼저 한다. 서버 확인은 이것과 별개로, 요청마다 서버가 한다.
     */
    private fun handle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { /* 실패해도 다음 restore 에서 다시 한다 */ }
        }
        Prefs.setPurchaseToken(app, purchase.purchaseToken)
        onStatus(app.getString(R.string.billing_subscribed))
    }

    private fun describe(result: BillingResult): String {
        val reason = result.debugMessage.ifEmpty { "code ${result.responseCode}" }
        return app.getString(R.string.billing_failed, reason)
    }

    companion object {
        /** Play Console 에 만들 구독 상품 ID. 여기와 콘솔이 글자 하나까지 같아야 한다. */
        const val PRODUCT_ID = "ai_unlimited_monthly"
    }
}
