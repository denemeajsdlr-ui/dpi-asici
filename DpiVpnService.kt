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

class DpiVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.dpibypass.START"
        const val ACTION_STOP = "com.dpibypass.STOP"
        private const val MTU = 8500
        val status = MutableStateFlow(VpnStatus.STOPPED)
        val error = MutableStateFlow("")
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunnelRunning = false

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        showForeground()
        Thread { startAll() }.start()
        return START_NOT_STICKY
    }

    private fun startAll() {
        try {
            doStart()
        } catch (t: Throwable) {
            AppLog.w("HATA: " + Log.getStackTraceString(t).take(1200))
            fail("Beklenmeyen hata: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun doStart() {
        status.value = VpnStatus.CONNECTING
        error.value = ""
        AppLog.w("--- Bağlanma başladı")
        val prefs = Prefs(this)

        // 1) Tünel kütüphanesi yükleniyor mu? (VPN açılmadan önce kontrol)
        AppLog.w("cihaz: Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        val libFile = File(applicationInfo.nativeLibraryDir, "libhev-socks5-tunnel.so")
        AppLog.w("kütüphane dosyası: ${libFile.exists()} boyut=${if (libFile.exists()) libFile.length() else 0}")
        try {
            AppLog.w("loadLibrary çağrılıyor…")
            System.loadLibrary("hev-socks5-tunnel")
            AppLog.w("loadLibrary bitti")
            Class.forName("hev.htproxy.TProxyService")
            AppLog.w("1/5 tünel sınıfı hazır")
        } catch (t: Throwable) {
            AppLog.w("Kütüphane hatası: " + Log.getStackTraceString(t).take(800))
            fail("Tünel kütüphanesi yüklenemedi: ${t.message ?: t.javaClass.simpleName}")
            return
        }

        // 2) DPI motoru
        if (!Byedpi.start(this, prefs.activeArgs())) {
            fail("DPI motoru başlatılamadı. Başka bir yöntem dene.")
            return
        }
        AppLog.w("2/5 DPI motoru çalışıyor (${prefs.activeArgs()})")

        // 3) VPN arayüzü
        val b = Builder()
            .setSession("DPI Aşıcı")
            .setMtu(MTU)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00::1", 128)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        try {
            if (prefs.onlyDiscord) b.addAllowedApplication("com.discord")
            else b.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            fail("Discord yüklü değil.")
            return
        }

        val fd = try { b.establish() } catch (e: Exception) { null }
        if (fd == null) { fail("VPN kurulamadı."); return }
        tun = fd
        AppLog.w("3/5 VPN arayüzü kuruldu")

        // 4) Tünel motoru
        val cfg = File(filesDir, "tproxy.yml")
        cfg.writeText(
            """
            tunnel:
              mtu: $MTU
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
        AppLog.w("4/5 tünel başlatılıyor…")
        TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
        tunnelRunning = true
        AppLog.w("5/5 tünel başlatıldı - BAĞLI")
        status.value = VpnStatus.CONNECTED
    }

    private fun fail(msg: String) {
        cleanup()
        error.value = msg
        status.value = VpnStatus.ERROR
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        if (tunnelRunning) {
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
            tunnelRunning = false
        }
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        Byedpi.stop()
    }

    private fun stopAll() {
        cleanup()
        status.value = VpnStatus.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopAll() }

    override fun onDestroy() {
        cleanup()
        if (status.value != VpnStatus.ERROR) status.value = VpnStatus.STOPPED
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
            .setContentTitle("DPI Aşıcı çalışıyor")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(0, "Durdur", stop)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}
