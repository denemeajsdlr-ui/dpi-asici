package com.dpibypass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import hev.htproxy.TProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

enum class VpnStatus { STOPPED, CONNECTING, CONNECTED, ERROR }

/** Bu servis ":vpn" adlı AYRI bir süreçte çalışır: native bir çökme olsa bile uygulama arayüzü kapanmaz. */
class DpiVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.dpibypass.START"
        const val ACTION_STOP = "com.dpibypass.STOP"
        const val ACTION_STATUS = "com.dpibypass.STATUS"
        const val EXTRA_ARGS = "args"
        const val EXTRA_ONLY = "only"
        const val EXTRA_LANG = "lang"
        private const val HEV_MTU = 8500

        // Arayüz sürecindeki kopya; servis sürecinden yayın (broadcast) ile güncellenir.
        val status = MutableStateFlow(VpnStatus.STOPPED)
        val error = MutableStateFlow("")
        val engine = MutableStateFlow("")
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunnelRunning = false
    private var kTun: KotlinTun? = null
    private var tproxy: TProxyService? = null
    private var lang = "tr"

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        lang = intent?.getStringExtra(EXTRA_LANG) ?: "tr"
        showForeground()
        val args = intent?.getStringExtra(EXTRA_ARGS) ?: ""
        val only = intent?.getBooleanExtra(EXTRA_ONLY, false) ?: false
        Thread { startAll(args, only) }.start()
        return START_NOT_STICKY
    }

    private fun publish(s: VpnStatus, msg: String = "") {
        status.value = s
        error.value = msg
        try { File(filesDir, "status.txt").writeText(s.name) } catch (_: Exception) {}
        try {
            sendBroadcast(
                Intent(ACTION_STATUS).setPackage(packageName)
                    .putExtra("s", s.name).putExtra("m", msg).putExtra("e", engine.value)
            )
        } catch (_: Exception) {}
    }

    private fun startAll(args: String, only: Boolean) {
        try {
            doStart(args, only)
        } catch (t: Throwable) {
            AppLog.w("HATA: " + Log.getStackTraceString(t).take(1200))
            fail("err_unknown|${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun doStart(args: String, only: Boolean) {
        publish(VpnStatus.CONNECTING)
        AppLog.w("--- Bağlanma başladı: $args")

        val tryFlag = File(filesDir, "hev_trying")
        val brokenFlag = File(filesDir, "hev_broken")
        if (tryFlag.exists()) {
            brokenFlag.writeText("1"); tryFlag.delete()
            AppLog.w("Önceki deneme hev motorunu çökertmiş → yedek motor")
        }
        var useKotlin = brokenFlag.exists()

        if (!useKotlin) {
            tryFlag.writeText("1") // çökerse bu bayrak kalır ve bir sonraki sefer yedek motor seçilir
            try {
                AppLog.w("hev yükleniyor…")
                System.loadLibrary("hev-socks5-tunnel")
                Class.forName("hev.htproxy.TProxyService")
                AppLog.w("hev yüklendi")
            } catch (t: Throwable) {
                AppLog.w("hev yüklenemedi: " + Log.getStackTraceString(t).take(600))
                brokenFlag.writeText("1"); tryFlag.delete()
                useKotlin = true
            }
        }
        engine.value = if (useKotlin) "Yedek (Kotlin)" else "hev"
        AppLog.w("motor: ${engine.value}")

        if (!Byedpi.start(this, args)) {
            fail("err_dpi")
            return
        }
        AppLog.w("DPI motoru çalışıyor")

        val b = Builder().setSession("DPI Aşıcı")
        if (useKotlin) {
            b.setMtu(1500).addAddress("10.8.0.2", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd00::1", 128).addRoute("::", 0) // IPv6 bilerek kara deliğe
                .setBlocking(true)
        } else {
            b.setMtu(HEV_MTU).addAddress("198.18.0.1", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd00::1", 128).addRoute("::", 0)
        }
        b.addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")

        try {
            if (only) b.addAllowedApplication("com.discord")
            else b.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            fail("err_discord")
            return
        }

        val fd = try { b.establish() } catch (e: Exception) { null }
        if (fd == null) { fail("err_vpn"); return }
        tun = fd
        AppLog.w("VPN arayüzü kuruldu")

        if (useKotlin) {
            kTun = KotlinTun(fd, Byedpi.PORT).also { it.start() }
            AppLog.w("yedek motor başladı")
        } else {
            val cfg = File(filesDir, "tproxy.yml")
            cfg.writeText(
                """
                tunnel:
                  mtu: $HEV_MTU
                  ipv4: 198.18.0.1
                  ipv6: 'fd00::1'
                socks5:
                  port: ${Byedpi.PORT}
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  task-stack-size: 81920
                  log-level: warn
                """.trimIndent()
            )
            AppLog.w("hev başlatılıyor…")
            val t = TProxyService()
            tproxy = t
            t.TProxyStartService(cfg.absolutePath, fd.fd)
            tunnelRunning = true
            try { Thread.sleep(1500) } catch (_: Exception) {}
            tryFlag.delete() // buraya kadar çökmediyse hev sağlam
            AppLog.w("hev başladı")
        }
        publish(VpnStatus.CONNECTED)
        AppLog.w("BAĞLI")
    }

    private fun fail(msg: String) {
        File(filesDir, "hev_trying").delete()
        cleanup()
        publish(VpnStatus.ERROR, msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        if (tunnelRunning) {
            try { tproxy?.TProxyStopService() } catch (_: Throwable) {}
            tunnelRunning = false
        }
        try { kTun?.stop() } catch (_: Exception) {}
        kTun = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        Byedpi.stop()
    }

    private fun stopAll() {
        cleanup()
        publish(VpnStatus.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopAll() }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "vpn")
            .setContentTitle(Strings.get(lang, "notif_title"))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(0, Strings.get(lang, "notif_stop"), stop)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}
