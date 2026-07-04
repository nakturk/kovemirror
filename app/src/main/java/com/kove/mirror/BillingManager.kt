package com.kove.mirror

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SubStatus { ACTIVE, TRIAL, EXPIRED, UNKNOWN }

class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val SUB_PRODUCT_ID = "kove_mirror_monthly"
    }

    private val _subStatus = MutableStateFlow(SubStatus.UNKNOWN)
    val subStatus: StateFlow<SubStatus> = _subStatus.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val trialManager = TrialManager(context)

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing client connected")
                    queryProductDetails()
                    queryActivePurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    fallbackToTrialState()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing client disconnected, retrying...")
                // In production, add a retry policy here
                fallbackToTrialState()
            }
        })
    }

    private fun queryProductDetails() {
        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUB_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                if (productDetailsList.isNotEmpty()) {
                    _productDetails.value = productDetailsList[0]
                }
            }
        }
    }

    fun queryActivePurchases() {
        if (!billingClient.isReady) {
            startConnection()
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            } else {
                fallbackToTrialState()
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>?) {
        var isSubscribed = false
        purchases?.forEach { purchase ->
            if (purchase.products.contains(SUB_PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                isSubscribed = true
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase.purchaseToken)
                }
            }
        }

        if (isSubscribed) {
            _subStatus.value = SubStatus.ACTIVE
        } else {
            fallbackToTrialState()
        }
    }

    private fun acknowledgePurchase(purchaseToken: String) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        CoroutineScope(Dispatchers.IO).launch {
            billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Purchase acknowledged successfully")
                    _subStatus.value = SubStatus.ACTIVE
                }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "User canceled the purchase flow")
        } else {
            Log.e(TAG, "Error during purchase flow: ${billingResult.debugMessage}")
        }
    }

    fun launchBillingFlow(activity: Activity) {
        val productDetails = _productDetails.value
        if (productDetails != null) {
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return
            
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build()
            )
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            billingClient.launchBillingFlow(activity, billingFlowParams)
        } else {
            Log.e(TAG, "Product details not available yet.")
        }
    }

    private fun fallbackToTrialState() {
        if (trialManager.isTrialExpired()) {
            _subStatus.value = SubStatus.EXPIRED
        } else {
            _subStatus.value = SubStatus.TRIAL
        }
    }

    fun isAccessGranted(): Boolean {
        val status = _subStatus.value
        if (status == SubStatus.UNKNOWN) {
            // If unknown (offline), fallback to trial manager
            return !trialManager.isTrialExpired()
        }
        return status == SubStatus.ACTIVE || status == SubStatus.TRIAL
    }
}
