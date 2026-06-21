package com.example.hfpdetector

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object HfpCompat {
    private const val PROFILE_HEADSET_CLIENT = 16

    fun canRunCheck(context: Context): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) return false
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun checkAsync(context: Context, timeoutMs: Long = 5000, cb: (String) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            cb("NO"); return
        }
        if (!adapter.isEnabled) {
            cb("NO"); return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                cb("UNKNOWN"); return
            }
        }

        var done = false
        val h = Handler(Looper.getMainLooper())

        val ok = try {
            adapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == PROFILE_HEADSET_CLIENT && !done) {
                            done = true
                            Prefs.setHfpSupport(context, "YES")
                            cb("YES")
                            adapter.closeProfileProxy(profile, proxy)
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                },
                PROFILE_HEADSET_CLIENT
            )
        } catch (_: Throwable) {
            false
        }

        if (!ok) {
            Prefs.setHfpSupport(context, "NO")
            cb("NO")
            return
        }

        h.postDelayed({
            if (!done) {
                Prefs.setHfpSupport(context, "NO")
                cb("NO")
            }
        }, timeoutMs)
    }
}
