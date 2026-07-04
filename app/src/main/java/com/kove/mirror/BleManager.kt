package com.kove.mirror

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.*

@SuppressLint("MissingPermission")
class BleManager(private val context: Context, private val logCallback: (String) -> Unit) {

    companion object {
        val SERVICE_UUID = UUID.fromString("0000e0ff-3c17-d293-8e48-14fe2e4da212")
        val WRITE_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CHAR_UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isConnected = false
    private var targetMac: String? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                sendHeartbeat()
                handler.postDelayed(this, 5000)
            }
        }
    }

    fun connect(macAddress: String) {
        disconnect()
        targetMac = macAddress
        logCallback("🔵 Bluetooth BLE bağlantısı başlatılıyor: $macAddress")
        
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            logCallback("❌ Bluetooth desteklenmiyor")
            return
        }
        
        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: Exception) {
            logCallback("❌ Geçersiz MAC adresi: ${e.message}")
            return
        }

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        handler.removeCallbacks(heartbeatRunnable)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeChar = null
        if (isConnected) {
            isConnected = false
            logCallback("🔴 Bluetooth BLE bağlantısı kesildi")
        }
    }

    fun sendJson(json: JSONObject) {
        val payload = json.toString()
        sendRaw(payload.toByteArray())
    }

    private val sendQueue = LinkedList<ByteArray>()
    private var isProcessingQueue = false

    fun sendRaw(data: ByteArray) {
        synchronized(sendQueue) {
            sendQueue.add(data)
        }
        if (!isProcessingQueue) {
            isProcessingQueue = true
            processNextQueueItem()
        }
    }

    private fun processNextQueueItem() {
        val data = synchronized(sendQueue) {
            if (sendQueue.isEmpty()) {
                isProcessingQueue = false
                null
            } else {
                sendQueue.removeFirst()
            }
        } ?: return

        val gatt = bluetoothGatt
        val char = writeChar

        if (gatt != null && char != null) {
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val success = gatt.writeCharacteristic(char)
            if (!success) {
                logCallback("⚠️ BLE paket yazma hatası, 150ms sonra tekrar denenecek")
                synchronized(sendQueue) {
                    sendQueue.addFirst(data)
                }
            }
        } else {
            logCallback("⚠️ BLE hazır değil, paket atıldı")
        }

        handler.postDelayed({
            processNextQueueItem()
        }, 150)
    }

    private fun sendHeartbeat() {
        try {
            // Standart ayna durum bildirimi veya boşluk
            val json = JSONObject().apply {
                put("msg_id", 25)
                put("msg_type", 24)
                put("msg_source", 2)
                put("status", 1)
            }
            sendJson(json)
        } catch (e: Exception) {
            logCallback("⚠️ Heartbeat hatası: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logCallback("🟢 Bluetooth bağlandı, servisler keşfediliyor...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                logCallback("🔴 Bluetooth bağlantısı koptu (GATT disconnected)")
                handler.removeCallbacks(heartbeatRunnable)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    writeChar = service.getCharacteristic(WRITE_CHAR_UUID)
                    val notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
                    
                    if (writeChar != null && notifyChar != null) {
                        logCallback("🔓 BLE Servisleri bulundu. Dinleme başlatılıyor...")
                        enableNotification(gatt, notifyChar)
                    } else {
                        logCallback("❌ Gerekli BLE karakteristikleri bulunamadı")
                    }
                } else {
                    // Diğer olası UUID'leri dene
                    tryAlternativeServices(gatt)
                }
            } else {
                logCallback("❌ BLE Servis keşfi başarısız: status=$status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback("✅ BLE Handshake (Notification) aktif!")
                isConnected = true
                
                // Başlangıç paketlerini gönder
                sendInitPackets()
                
                // Heartbeat başlat
                handler.post(heartbeatRunnable)
            } else {
                logCallback("❌ Descriptor yazma başarısız: status=$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            val text = String(data)
            logCallback("📥 TFT -> BLE: $text")
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
                    logCallback("🔓 BLE Servisi bulundu (Alternatif: $uuid). Dinleme başlatılıyor...")
                    enableNotification(gatt, notifyChar)
                    return
                }
            }
        }
        logCallback("❌ Uyumlu bir ThinkerRide BLE servisi bulunamadı")
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun sendInitPackets() {
        try {
            // 1) Pair info
            val pair = JSONObject().apply {
                put("msg_id", 27)
                put("func", "PAIR")
                put("act", "get_pairinfo")
            }
            sendJson(pair)
            
            // 2) Version Code
            val version = JSONObject().apply {
                put("msg_id", 13)
            }
            sendJson(version)

            // 3) Language
            val lang = JSONObject().apply {
                put("msg_id", 25)
                put("msg_type", 18)
                put("msg_source", 2)
                put("language", 2)
            }
            sendJson(lang)

            // 4) Saat Eşitleme (Clock Sync)
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val timeJson = JSONObject().apply {
                put("msg_id", 11)
                put("time", dateStr)
                put("tag", -1)
            }
            sendJson(timeJson)

            // 5) Yansıtma Durumu Aktif Et (setMirrorStatus)
            val mirrorStatus = JSONObject().apply {
                put("msg_id", 25)
                put("msg_type", 23)
                put("msg_source", 2)
                put("status", 1)
            }
            sendJson(mirrorStatus)

            // 6) Kayıt Durumu Aktif Et (setRecordStatus)
            val recordStatus = JSONObject().apply {
                put("msg_id", 25)
                put("msg_type", 21)
                put("msg_source", 2)
                put("status", 1)
            }
            sendJson(recordStatus)
            
            logCallback("📤 BLE Başlangıç el sıkışma paketleri gönderildi (Saat eşitleme ve Yansıtma Aktif dahil: $dateStr)")
        } catch (e: Exception) {
            logCallback("⚠️ El sıkışma paketleri oluşturulamadı: ${e.message}")
        }
    }
}
