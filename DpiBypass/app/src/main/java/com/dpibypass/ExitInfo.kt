package com.dpibypass

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Android'in tuttuğu "uygulama neden kapandı" kaydını okur (API 30+). Native çökmenin ayrıntısını da içerir. */
object ExitInfo {
    private val names = mapOf(
        1 to "KENDİ_ÇIKIŞI", 2 to "SİNYAL", 3 to "BELLEK_AZ", 4 to "JAVA_ÇÖKME",
        5 to "NATIVE_ÇÖKME", 6 to "ANR", 7 to "BAŞLATMA_HATASI", 10 to "KULLANICI_İSTEDİ",
        13 to "DİĞER", 14 to "DONDURULDU"
    )

    private fun printable(b: ByteArray): String {
        val sb = StringBuilder()
        val run = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c in 32..126) run.append(c.toChar())
            else {
                if (run.length >= 6) sb.append(run).append(" | ")
                run.setLength(0)
            }
        }
        if (run.length >= 6) sb.append(run)
        return sb.toString()
    }

    fun log(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val sp = ctx.getSharedPreferences("dpi_prefs", Context.MODE_PRIVATE)
            val last = sp.getLong("last_exit_ts", 0L)
            var maxTs = last
            for (e in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6).sortedBy { it.timestamp }) {
                if (e.timestamp <= last) continue
                maxTs = maxOf(maxTs, e.timestamp)
                AppLog.w("ÇIKIŞ: ${names[e.reason] ?: e.reason.toString()} kod=${e.status} süreç=${e.processName} ${e.description ?: ""}")
                if (e.reason == 5) {
                    val bytes = try { e.traceInputStream?.use { it.readBytes() } } catch (_: Throwable) { null }
                    if (bytes != null) AppLog.w("NATIVE_İZ: " + printable(bytes).take(1800))
                }
            }
            sp.edit().putLong("last_exit_ts", maxTs).apply()
        } catch (_: Throwable) {}
    }
}
