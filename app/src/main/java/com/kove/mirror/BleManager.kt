package com.kove.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.*

class BleManager(private val context: Context, private val logCallback: (String) -> Unit) {

    companion object {
        val SERVICE_UUID = UUID.fromString("0000e0ff-3c17-d293-8e48-14fe2e4da212")
        val WRITE_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CHAR_UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var isConnected = false
    @Volatile private var isStopping = false
    @Volatile private var reconnectAttempt = 0
    @Volatile private var targetMac: String? = null

    var onMirrorRequested: (() -> Unit)? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                sendHeartbeat()
                handler.postDelayed(this, 5000)
            }
        }
    }

    private val reconnectRunnable = Runnable {
        if (!isStopping && !isConnected) {
            val mac = targetMac
            if (!mac.isNullOrEmpty()) {
                logCallback("🔄 BLE reconnecting to $mac (attempt ${reconnectAttempt + 1})...")
                doConnect(mac)
            }
        }
    }

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun connect(macAddress: String) {
        isStopping = false
        reconnectAttempt = 0
        targetMac = macAddress
        doConnect(macAddress)
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(macAddress: String) {
        cleanupGatt()

        if (!hasBluetoothPermission()) {
            logCallback("⚠️ BLUETOOTH_CONNECT permission not granted. Please grant Nearby Devices permission in app settings.")
            return
        }

        logCallback(DebugLogger.getString(R.string.log_ble_conn_starting, macAddress))

        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: SecurityException) {
            logCallback("❌ Bluetooth permission denied: ${e.message}")
            return
        } ?: run {
            logCallback(DebugLogger.getString(R.string.log_ble_not_supported))
            return
        }

        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: SecurityException) {
            logCallback("❌ Bluetooth permission denied for remote device: ${e.message}")
            return
        } catch (e: Exception) {
            logCallback(DebugLogger.getString(R.string.log_ble_invalid_mac, e.message ?: ""))
            return
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            logCallback("❌ SecurityException connecting BLE: ${e.message}. Please grant Nearby Devices permission.")
        } catch (e: Exception) {
            logCallback("❌ Error connecting BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanupGatt() {
        bluetoothGatt?.let { gatt ->
            try { gatt.disconnect() } catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
        }
        bluetoothGatt = null
        writeChar = null
    }

    private fun scheduleReconnect() {
        if (isStopping) return
        val delay = minOf(1000L * (1 shl reconnectAttempt), 16000L)
        reconnectAttempt++
        logCallback("⏳ BLE retry in ${delay / 1000}s (attempt $reconnectAttempt)...")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    fun disconnect() {
        isStopping = true
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(queueRunnable)
        synchronized(sendQueue) {
            sendQueue.clear()
        }
        isDrainScheduled = false
        cleanupGatt()
        if (isConnected) {
            isConnected = false
            logCallback(DebugLogger.getString(R.string.log_ble_disconnected))
        }
    }

    fun sendJson(json: JSONObject) {
        val payload = json.toString()
        sendRaw(payload.toByteArray())
    }

    private val sendQueue = LinkedList<ByteArray>()
    private var isDrainScheduled = false

    private val queueRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val data = synchronized(sendQueue) {
                if (sendQueue.isEmpty()) {
                    isDrainScheduled = false
                    null
                } else {
                    sendQueue.removeFirst()
                }
            } ?: return

            val gatt = bluetoothGatt
            val char = writeChar

            if (gatt != null && char != null) {
                try {
                    char.value = data
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val success = gatt.writeCharacteristic(char)
                    if (!success) {
                        logCallback("⚠️ BLE packet write error, retrying in 150ms")
                        synchronized(sendQueue) {
                            sendQueue.addFirst(data)
                        }
                    }
                } catch (e: SecurityException) {
                    logCallback("❌ BLE write permission error: ${e.message}")
                } catch (e: Exception) {
                    logCallback("⚠️ BLE write exception: ${e.message}")
                }
            } else {
                logCallback("⚠️ BLE not ready, packet dropped")
            }

            val hasMore = synchronized(sendQueue) { sendQueue.isNotEmpty() }
            if (hasMore) {
                isDrainScheduled = true
                handler.postDelayed(this, 150)
            } else {
                isDrainScheduled = false
            }
        }
    }

    fun sendRaw(data: ByteArray) {
        synchronized(sendQueue) {
            sendQueue.add(data)
        }
        handler.post {
            ensureDrainerRunning()
        }
    }

    private fun ensureDrainerRunning() {
        if (!isDrainScheduled) {
            val hasItems = synchronized(sendQueue) { sendQueue.isNotEmpty() }
            if (hasItems) {
                isDrainScheduled = true
                handler.removeCallbacks(queueRunnable)
                handler.post(queueRunnable)
            }
        }
    }

    private fun sendHeartbeat() {
        try {
            val json = JSONObject().apply {
                put("msg_id", 25)
                put("msg_type", 24)
                put("msg_source", 2)
                put("status", 1)
            }
            sendJson(json)
        } catch (e: Exception) {
            logCallback("⚠️ Heartbeat error: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logCallback("🔴 Bluetooth connection error: status=$status, newState=$newState")
                isConnected = false
                handler.removeCallbacks(heartbeatRunnable)
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                    writeChar = null
                }
                scheduleReconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logCallback("🟢 Bluetooth connected, requesting MTU (512)...")
                val requested = try {
                    gatt.requestMtu(512)
                } catch (e: SecurityException) {
                    logCallback("❌ Permission error requesting MTU: ${e.message}")
                    false
                } catch (e: Exception) {
                    logCallback("⚠️ Error requesting MTU: ${e.message}")
                    false
                }
                if (!requested) {
                    logCallback("⚠️ MTU request could not be initiated, discovering services directly...")
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        logCallback("❌ Permission error discovering services: ${e.message}")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                logCallback("🔴 Bluetooth connection lost (GATT disconnected)")
                handler.removeCallbacks(heartbeatRunnable)
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                    writeChar = null
                }
                scheduleReconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback("📶 BLE MTU negotiated: $mtu bytes")
            } else {
                logCallback("⚠️ BLE MTU negotiation failed: status=$status, current MTU: $mtu")
            }
            logCallback("🔍 Discovering BLE services...")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                logCallback("❌ Permission error discovering services: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback("🔍 Scanning BLE services...")
                gatt.services.forEach { s ->
                    logCallback("  [Service] ${s.uuid}")
                    s.characteristics.forEach { c ->
                        logCallback("    -> [Char] ${c.uuid} (Props: ${c.properties})")
                    }
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    writeChar = service.getCharacteristic(WRITE_CHAR_UUID)
                    val notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
                    
                    if (writeChar != null && notifyChar != null) {
                        logCallback("🔓 BLE Services found. Starting listener...")
                        enableNotification(gatt, notifyChar)
                    } else {
                        logCallback("❌ Required BLE characteristics not found")
                        tryAlternativeServices(gatt)
                    }
                } else {
                    tryAlternativeServices(gatt)
                }
            } else {
                logCallback("❌ BLE Service discovery failed: status=$status")
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                    writeChar = null
                }
                scheduleReconnect()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback("✅ BLE Handshake (Notification) active!")
                isConnected = true
                reconnectAttempt = 0
                
                sendInitPackets()
                handler.post(heartbeatRunnable)
            } else {
                logCallback("❌ Descriptor write failed: status=$status")
                try { gatt.close() } catch (_: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                    writeChar = null
                }
                scheduleReconnect()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            val text = String(data)
            logCallback("📥 TFT -> BLE: $text")
            
            HandlebarKeyManager.processJson(text)

            try {
                val json = JSONObject(text)
                if (json.optInt("msg_id") == 27 && json.optString("act") == "send_pairresult" && json.optInt("result") == 1) {
                    logCallback("✅ Pairing confirmed, sending Mirror commands...")
                    
                    onMirrorRequested?.invoke()
                    
                    val mirrorStatus = JSONObject().apply {
                        put("msg_id", 25)
                        put("msg_type", 23)
                        put("msg_source", 2)
                        put("status", 1)
                    }
                    sendJson(mirrorStatus)

                    val recordStatus = JSONObject().apply {
                        put("msg_id", 25)
                        put("msg_type", 21)
                        put("msg_source", 2)
                        put("status", 1)
                    }
                    sendJson(recordStatus)
                }
            } catch (_: Exception) {}
        }
    }

    private fun tryAlternativeServices(gatt: BluetoothGatt) {
        val altUUIDs = listOf(
            UUID.fromString("0000e0ff-3c17-d293-8e48-14fe2e4da213"),
            UUID.fromString("0000e0ff-3e17-d293-8e48-14fe2e4da212"),
            UUID.fromString("0000e0ff-4017-d293-8e48-14fe2e4da212")
        )
        for (uuid in altUUIDs) {
            val service = gatt.getService(uuid)
            if (service != null) {
                writeChar = service.getCharacteristic(WRITE_CHAR_UUID)
                val notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
                if (writeChar != null && notifyChar != null) {
                    logCallback("🔓 BLE Service found (Alternative: $uuid). Starting listener...")
                    enableNotification(gatt, notifyChar)
                    return
                }
            }
        }

        logCallback("⚠️ Known service UUIDs not found. Starting dynamic scan...")
        for (service in gatt.services) {
            val wChar = service.getCharacteristic(WRITE_CHAR_UUID)
            val nChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
            if (wChar != null && nChar != null) {
                writeChar = wChar
                logCallback("🔓 BLE Service found dynamically! (Service: ${service.uuid}). Starting listener...")
                enableNotification(gatt, nChar)
                return
            }
        }

        logCallback("❌ No compatible ThinkerRide BLE service found")
        try { gatt.close() } catch (_: Exception) {}
        if (bluetoothGatt == gatt) {
            bluetoothGatt = null
            writeChar = null
        }
        scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        } catch (e: SecurityException) {
            logCallback("❌ SecurityException enabling notification: ${e.message}")
        }
    }

    fun sendInitPackets() {
        try {
            val pair = JSONObject().apply {
                put("msg_id", 27)
                put("func", "PAIR")
                put("act", "get_pairinfo")
            }
            sendJson(pair)
            
            val version = JSONObject().apply {
                put("msg_id", 13)
            }
            sendJson(version)

            val lang = JSONObject().apply {
                put("msg_id", 25)
                put("msg_type", 18)
                put("msg_source", 2)
                put("language", 2)
            }
            sendJson(lang)

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val timeJson = JSONObject().apply {
                put("msg_id", 11)
                put("time", dateStr)
                put("tag", -1)
            }
            sendJson(timeJson)
            
            logCallback("📤 BLE initialization handshake packets sent")
        } catch (e: Exception) {
            logCallback("⚠️ Failed to create handshake packets: ${e.message}")
        }
    }
}
