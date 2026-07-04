package com.kove.mirror

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PaywallActivity : AppCompatActivity() {

    private lateinit var billingManager: BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paywall)

        billingManager = (application as KoveApplication).billingManager

        val btnSubscribe = findViewById<Button>(R.id.btnSubscribe)
        
        btnSubscribe.setOnClickListener {
            billingManager.launchBillingFlow(this)
        }

        findViewById<Button>(R.id.btnExit).setOnClickListener {
            finishAffinity() // Close all activities and exit app
        }

        lifecycleScope.launch {
            billingManager.subStatus.collectLatest { status ->
                if (status == SubStatus.ACTIVE) {
                    Toast.makeText(this@PaywallActivity, "Abonelik aktif! KoveMirror'a hoş geldiniz.", Toast.LENGTH_LONG).show()
                    finish() // Return to MainActivity
                }
            }
        }

        lifecycleScope.launch {
            billingManager.productDetails.collectLatest { product ->
                if (product != null) {
                    val price = product.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                    if (price != null) {
                        btnSubscribe.text = "Premium Satın Al ($price / Ay)"
                    }
                }
            }
        }
    }

    // Prevent user from pressing back to return to the app
    override fun onBackPressed() {
        finishAffinity()
    }
}
