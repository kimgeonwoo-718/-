package com.android.billingclient.api

import android.app.Activity
import android.content.Context

class BillingResult {
    val responseCode: Int = 0
    val debugMessage: String = ""
}

class ProductDetails {
    val subscriptionOfferDetails: List<SubscriptionOfferDetails>? = null
    class SubscriptionOfferDetails { val offerToken: String = "" }
}

class Purchase {
    val purchaseState: Int = 0
    val purchaseToken: String = ""
    val isAcknowledged: Boolean = false
    val products: List<String> = emptyList()
    object PurchaseState { const val PURCHASED = 1; const val PENDING = 2 }
}

fun interface PurchasesUpdatedListener {
    fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?)
}

interface BillingClientStateListener {
    fun onBillingSetupFinished(result: BillingResult)
    fun onBillingServiceDisconnected()
}

class PendingPurchasesParams {
    class Builder {
        fun enableOneTimeProducts(): Builder = this
        fun build(): PendingPurchasesParams = PendingPurchasesParams()
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class QueryPurchasesParams {
    class Builder {
        fun setProductType(type: String): Builder = this
        fun build(): QueryPurchasesParams = QueryPurchasesParams()
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class QueryProductDetailsParams {
    class Product {
        class Builder {
            fun setProductId(id: String): Builder = this
            fun setProductType(type: String): Builder = this
            fun build(): Product = Product()
        }
        companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
    }
    class Builder {
        fun setProductList(products: List<Product>): Builder = this
        fun build(): QueryProductDetailsParams = QueryProductDetailsParams()
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class BillingFlowParams {
    class ProductDetailsParams {
        class Builder {
            fun setProductDetails(details: ProductDetails): Builder = this
            fun setOfferToken(token: String): Builder = this
            fun build(): ProductDetailsParams = ProductDetailsParams()
        }
        companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
    }
    class Builder {
        fun setProductDetailsParamsList(list: List<ProductDetailsParams>): Builder = this
        fun build(): BillingFlowParams = BillingFlowParams()
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class AcknowledgePurchaseParams {
    class Builder {
        fun setPurchaseToken(token: String): Builder = this
        fun build(): AcknowledgePurchaseParams = AcknowledgePurchaseParams()
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class BillingClient {
    object BillingResponseCode {
        const val OK = 0
        const val USER_CANCELED = 1
        const val SERVICE_DISCONNECTED = 2
        const val ITEM_ALREADY_OWNED = 7
        const val BILLING_UNAVAILABLE = 3
        const val DEVELOPER_ERROR = 5
        const val ERROR = 6
    }
    object ProductType { const val SUBS = "subs"; const val INAPP = "inapp" }
    class Builder {
        fun setListener(listener: PurchasesUpdatedListener): Builder = this
        fun enablePendingPurchases(params: PendingPurchasesParams): Builder = this
        fun build(): BillingClient = BillingClient()
    }
    companion object { @JvmStatic fun newBuilder(context: Context): Builder = Builder() }
    val isReady: Boolean = false
    fun startConnection(listener: BillingClientStateListener) {}
    fun endConnection() {}
    fun queryPurchasesAsync(params: QueryPurchasesParams, listener: (BillingResult, MutableList<Purchase>) -> Unit) {}
    fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: (BillingResult, MutableList<ProductDetails>) -> Unit) {}
    fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult = BillingResult()
    fun acknowledgePurchase(params: AcknowledgePurchaseParams, listener: (BillingResult) -> Unit) {}
}
