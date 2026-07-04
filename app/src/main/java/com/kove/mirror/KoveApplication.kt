package com.kove.mirror

import android.app.Application

class KoveApplication : Application() {

    lateinit var billingManager: BillingManager
        private set

    override fun onCreate() {
        super.onCreate()
        billingManager = BillingManager(this)
        billingManager.startConnection()
    }
}
