package com.dpibypass

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama içi basit kayıt: çökme olursa sebebi bir sonraki açılışta görebilelim. */
object AppLog {
    private var file: File? = null
    private var handlerInstalled = false
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "app.log")
        if (!handlerInstalled) {
            handlerInstalled = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                w("COKME: " + Log.getStackTraceString(e).take(1500))
                prev?.uncaughtException(t, e)
            }
        }
    }

    @Synchronized
    fun w(msg: String) {
        try {
            val f = file ?: return
            f.appendText("${fmt.format(Date())} $msg\n")
            if (f.length() > 20000) f.writeText(f.readText().takeLast(8000))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun read(): String = try { file?.readText()?.takeLast(2500) ?: "" } catch (_: Exception) { "" }

    @Synchronized
    fun clear() { try { file?.writeText("") } catch (_: Exception) {} }
}
