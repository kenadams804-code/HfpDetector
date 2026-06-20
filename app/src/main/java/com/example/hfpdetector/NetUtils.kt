package com.example.hfpdetector

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NetUtils {

    fun getBroadcastAddress(context: Context): InetAddress? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val dhcp = wifi.dhcpInfo ?: return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val quads = ByteArray(4)
        for (k in 0..3) {
            quads[k] = (broadcast shr (k * 8) and 0xFF).toByte()
        }
        return InetAddress.getByAddress(quads)
    }

    fun getLocalWifiIp(context: Context): String {
        return try {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return ""
            val ipInt = wifi.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return ""
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt)
            InetAddress.getByAddress(bb.array()).hostAddress ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
