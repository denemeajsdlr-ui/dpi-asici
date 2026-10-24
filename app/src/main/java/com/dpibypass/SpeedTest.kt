package com.dpibypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

data class SpeedResult(val pingMs: Int, val downMbps: Double, val upMbps: Double)

/** Ping + indirme + yükleme ölçümü (Cloudflare hız sunucuları). viaProxy=true: DPI aşıcı üzerinden. */
object SpeedTest {
    private const val HOST = "https://speed.cloudflare.com"

    private fun open(path: String, viaProxy: Boolean): HttpURLConnection {
        val u = URL(HOST + path)
        val c = (if (viaProxy) u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", Byedpi.PORT)))
        else u.openConnection()) as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.useCaches = false
        return c
    }

    private fun ping(viaProxy: Boolean): Int {
        val times = ArrayList<Long>()
        for (i in 0 until 6) {
            val t0 = System.nanoTime()
            val c = open("/__down?bytes=0", viaProxy)
            c.responseCode
            c.inputStream.use { it.readBytes() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (i > 0) times.add(ms) // ilk istek TLS el sıkışmasını da içerir, sayma
        }
        times.sort()
        return times[times.size / 2].toInt()
    }

    private fun download(viaProxy: Boolean): Double {
        val c = open("/__down?bytes=25000000", viaProxy)
        val t0 = System.nanoTime()
        var total = 0L
        c.inputStream.use { s ->
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (System.nanoTime() - t0 > 7_000_000_000L) break
            }
        }
        c.disconnect()
        val sec = (System.nanoTime() - t0) / 1e9
        return total * 8 / sec / 1e6
    }

    private fun upload(viaProxy: Boolean): Double {
        val size = 6_000_000
        val c = open("/__up", viaProxy)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(size)
        val chunk = ByteArray(65536)
        val t0 = System.nanoTime()
        c.outputStream.use { o ->
            var sent = 0
            while (sent < size) {
                val n = minOf(chunk.size, size - sent)
                o.write(chunk, 0, n)
                sent += n
            }
        }
        c.responseCode
        try { c.inputStream.use { it.readBytes() } } catch (_: Exception) {}
        val sec = (System.nanoTime() - t0) / 1e9
        return size * 8 / sec / 1e6
    }

    suspend fun run(viaProxy: Boolean): SpeedResult? = withContext(Dispatchers.IO) {
        try {
            val p = ping(viaProxy)
            val d = download(viaProxy)
            val u = try { upload(viaProxy) } catch (e: Exception) { 0.0 }
            SpeedResult(p, d, u)
        } catch (e: Exception) {
            AppLog.w("hız testi hatası: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }
}
