package com.kove.mirror

import android.content.Context
import android.net.wifi.WifiManager
import java.nio.ByteOrder

object NetworkUtils {

    /**
     * Motosiklet hotspot'una bağlandıktan sonra telefonun aldığı IP'yi döner.
     * Telefon sunucu rolündedir — bu IP'nin port 17818'ine TFT bağlanır.
     */
    fun getWifiIpAddress(context: Context): String {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            var ip = dhcpInfo.ipAddress

            // Android little-endian'da saklar; network byte order'a çevir
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                ip = Integer.reverseBytes(ip)
            }
            "%d.%d.%d.%d".format(
                (ip shr 24) and 0xFF,
                (ip shr 16) and 0xFF,
                (ip shr 8)  and 0xFF,
                ip          and 0xFF
            )
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    /**
     * Gateway = motosikletin TBox IP'si (hotspot gateway)
     * Bilgi amaçlı loglanır — bağlantı yönü tersi (TFT bize bağlanır)
     */
    fun getGatewayAddress(context: Context): String {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            var gw = wm.dhcpInfo.gateway
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                gw = Integer.reverseBytes(gw)
            }
            "%d.%d.%d.%d".format(
                (gw shr 24) and 0xFF,
                (gw shr 16) and 0xFF,
                (gw shr 8)  and 0xFF,
                gw          and 0xFF
            )
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    @Suppress("DEPRECATION")
    fun getWifiSsid(context: Context): String {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.connectionInfo.ssid?.removeSurrounding("\"") ?: "Bağlı değil"
        } catch (e: Exception) {
            "Bilinmiyor"
        }
    }
}
