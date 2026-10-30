package com.kove.mirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteOrder

object NetworkUtils {

    /**
     * Motosiklet hotspot'una bağlandıktan sonra telefonun aldığı IP'yi döner.
     * Telefon sunucu rolündedir — bu IP'nin port 17818'ine TFT bağlanır.
     */
    fun getWifiIpAddress(context: Context, wifiNetwork: Network? = null): String {
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val targetNetwork = wifiNetwork ?: cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (targetNetwork != null) {
                val linkProps = cm.getLinkProperties(targetNetwork)
                val ipv4 = linkProps?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                if (!ipv4.isNullOrEmpty() && ipv4 != "0.0.0.0") {
                    return ipv4
                }
            }
        } catch (_: Exception) {}

        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            var ip = dhcpInfo.ipAddress
            if (ip != 0) {
                // Android little-endian'da saklar; network byte order'a çevir
                if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                    ip = Integer.reverseBytes(ip)
                }
                val ipStr = "%d.%d.%d.%d".format(
                    (ip shr 24) and 0xFF,
                    (ip shr 16) and 0xFF,
                    (ip shr 8)  and 0xFF,
                    ip          and 0xFF
                )
                if (ipStr != "0.0.0.0") return ipStr
            }
        } catch (_: Exception) {}

        return try {
            val wlan = NetworkInterface.getByName("wlan0")
            wlan?.inetAddresses?.toList()
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    /**
     * Gateway = motosikletin TBox IP'si (hotspot gateway)
     * Bilgi amaçlı loglanır — bağlantı yönü tersi (TFT bize bağlanır)
     */
    fun getGatewayAddress(context: Context, wifiNetwork: Network? = null): String {
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val targetNetwork = wifiNetwork ?: cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (targetNetwork != null) {
                val linkProps = cm.getLinkProperties(targetNetwork)
                val gw = linkProps?.routes
                    ?.mapNotNull { it.gateway }
                    ?.firstOrNull { it is Inet4Address && !it.isAnyLocalAddress && !it.isLoopbackAddress }
                    ?.hostAddress
                if (!gw.isNullOrEmpty() && gw != "0.0.0.0") {
                    return gw
                }
            }
        } catch (_: Exception) {}

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
