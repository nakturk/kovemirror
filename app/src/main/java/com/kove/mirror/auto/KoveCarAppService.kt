package com.kove.mirror.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto host connections.
 * Exposes KoveMirror's map and turn-by-turn navigation module to Android Auto.
 */
class KoveCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return KoveCarSession()
    }
}
