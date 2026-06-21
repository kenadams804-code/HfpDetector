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

    fun exportToDownloads(context: Context): Uri? {
        val app = context.applicationContext
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val fileName = "LanCall-Diag-$ts.zip"

        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/LanCall")
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { os ->
                writeZip(app, os)
            } ?: return null
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        return uri
    }

    private fun writeZip(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            addText(zip, "log/app-log.txt", AppLog.get(context))

            val prefs = JSONObject().apply {
                put("mode", Prefs.getMode(context))
                put("serviceEnabled", Prefs.isServiceEnabled(context))
                put("manualPairEnabled", Prefs.isManualPairEnabled(context))
                put("peerIp_saved", Prefs.getPeerIp(context))
                put("peerSeenTs", Prefs.getPeerSeenTs(context))
                put("peerSeenIp", Prefs.getPeerSeenIp(context))
                put("hfpSupport", Prefs.getHfpSupport(context))
                put("silencePstn", Prefs.isSilencePstn(context))
            }
            addText(zip, "status/prefs.json", prefs.toString(2))

            val device = JSONObject().apply {
                put("brand", Build.BRAND)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE)
                put("fingerprint", Build.FINGERPRINT)
            }
            addText(zip, "status/device.json", device.toString(2))
        }
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
