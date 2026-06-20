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

    fun intToIp(i: Int): String {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i)
        return InetAddress.getByAddress(bb.array()).hostAddress ?: "0.0.0.0"
    }
}
