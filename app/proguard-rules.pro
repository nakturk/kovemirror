-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Google Play Billing
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }

# Bluetooth GATT (Keep callbacks so they don't break when obfuscated)
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }

# EncryptedSharedPreferences (AndroidX Security)
-keep class androidx.security.crypto.** { *; }

# Keep domain models if any (for JSON serialization etc, not strictly needed right now)
#-keep class com.kove.mirror.model.** { *; }
