package com.kove.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class TrialManager(private val context: Context) {

    companion object {
        const val MAX_TRIAL_MS = 60L * 60L * 1000L // 60 minutes
        private const val PREFS_FILE = "kove_secure_prefs"
        private const val KEY_USED_MS = "trial_used_ms"
        private const val KEY_HASH = "trial_hash"
    }

    private val sharedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for some devices where EncryptedSharedPreferences might fail
            context.getSharedPreferences("kove_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    private val deviceId: String
        @SuppressLint("HardwareIds")
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"

    private fun generateHash(usedMs: Long): String {
        val input = "${deviceId}_${usedMs}_kove_secret_salt_99!"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getUsedTrialMs(): Long {
        val usedMs = sharedPrefs.getLong(KEY_USED_MS, 0L)
        val savedHash = sharedPrefs.getString(KEY_HASH, "")

        // Tamper check: If there's some usage recorded, the hash must match
        if (usedMs > 0 && savedHash != generateHash(usedMs)) {
            // Someone tampered with the XML file! Penalize by expiring the trial.
            return MAX_TRIAL_MS
        }
        return usedMs
    }

    fun getRemainingTrialMs(): Long {
        val remaining = MAX_TRIAL_MS - getUsedTrialMs()
        return if (remaining < 0) 0 else remaining
    }

    fun isTrialExpired(): Boolean {
        return getUsedTrialMs() >= MAX_TRIAL_MS
    }

    fun addUsageMs(ms: Long) {
        val current = getUsedTrialMs()
        val newUsed = current + ms
        saveUsage(if (newUsed > MAX_TRIAL_MS) MAX_TRIAL_MS else newUsed)
    }

    private fun saveUsage(usedMs: Long) {
        sharedPrefs.edit()
            .putLong(KEY_USED_MS, usedMs)
            .putString(KEY_HASH, generateHash(usedMs))
            .apply()
    }

    // Hidden developer reset method
    fun resetTrialForDeveloper() {
        saveUsage(0L)
    }
}
