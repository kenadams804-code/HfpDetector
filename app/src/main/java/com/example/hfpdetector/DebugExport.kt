package com.example.hfpdetector

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugExport {

    /** 导出 zip 到“下载/LanCall/”并返回 Uri（成功才有） */
    fun exportToDownloads(context: Context): Uri? {
        val app = context.applicationContext
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val fileName = "LanCall-Diag-$ts.zip"

        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            // 下载目录下建子文件夹
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/LanCall")
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { os ->
                writeZip(app, os)
            } ?: return null
        } catch (_: Throwable) {
            // 写失败就删掉空文件
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        return uri
    }

    private fun writeZip(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->

            // 1) app 日志
            addText(zip, "log/app-log.txt", AppLog.get(context))

            // 2) 关键状态（Prefs）
            val prefs = JSONObject().apply {
                put("mode", Prefs.getMode(context))
                put("serviceEnabled", Prefs.isServiceEnabled(context))
                put("manualPairEnabled", Prefs.isManualPairEnabled(context))
                put("peerIp", Prefs.getPeerIp(context))
                put("peerSeenTs", Prefs.getPeerSeenTs(context))
                put("peerSeenIp", Prefs.getPeerSeenIp(context))
                put("hfpSupport", Prefs.getHfpSupport(context))
                put("silencePstn", Prefs.isSilencePstn(context))
            }
            addText(zip, "status/prefs.json", prefs.toString(2))

            // 3) 设备信息（不收集 IMEI/手机号等敏感信息）
            val device = JSONObject().apply {
                put("brand", Build.BRAND)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE)
                put("fingerprint", Build.FINGERPRINT)
            }
            addText(zip, "status/device.json", device.toString(2))

            // 4) 权限状态（哪些开了/没开）
            val perms = JSONObject().apply {
                put("POST_NOTIFICATIONS_enabled", tryAreNotificationsEnabled(context))
                put("RECORD_AUDIO", hasPerm(context, android.Manifest.permission.RECORD_AUDIO))
                put("CAMERA", hasPerm(context, android.Manifest.permission.CAMERA))
                put("RECEIVE_SMS", hasPerm(context, android.Manifest.permission.RECEIVE_SMS))
                put("READ_SMS", hasPerm(context, android.Manifest.permission.READ_SMS))
                if (Build.VERSION.SDK_INT >= 31) {
                    put("BLUETOOTH_CONNECT", hasPerm(context, android.Manifest.permission.BLUETOOTH_CONNECT))
                }
            }
            addText(zip, "status/permissions.json", perms.toString(2))
        }
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun hasPerm(context: Context, perm: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun tryAreNotificationsEnabled(context: Context): Boolean {
        return try {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }
    }
}
