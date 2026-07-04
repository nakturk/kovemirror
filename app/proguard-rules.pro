-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Bluetooth GATT (Keep callbacks so they don't break when obfuscated)
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }

# Keep domain models if any (for JSON serialization etc, not strictly needed right now)
#-keep class com.kove.mirror.model.** { *; }
