package com.dpibypass

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** DPI aşma motorunu (ByeDPI) ayrı süreç olarak çalıştırır: 127.0.0.1:1080 SOCKS5. */
object Byedpi {
    const val PORT = 1080
    @Volatile private var proc: Process? = null

    @Synchronized
    fun start(ctx: Context, args: String): Boolean {
        stop()
        // Önceki çökmeden kalmış yetim ByeDPI süreci portu tutuyor olabilir
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f libbyedpi.so")).waitFor() } catch (_: Exception) {}
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libbyedpi.so")
        if (!bin.exists()) return false
        val cmd = listOf(bin.absolutePath, "--ip", "127.0.0.1", "--port", PORT.toString()) +
            args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return false
        }
        proc = p
        // çıktı tamponu dolup süreci kilitlemesin
        Thread { try { p.inputStream.bufferedReader().forEachLine { } } catch (_: Exception) {} }
            .apply { isDaemon = true; start() }
        repeat(25) {
            if (!p.isAlive) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 200) }
                return true
            } catch (_: Exception) {
                Thread.sleep(120)
            }
        }
        return false
    }

    @Synchronized
    fun stop() {
        proc?.let {
            it.destroy()
            try { if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly() } catch (_: Exception) {}
        }
        proc = null
    }
}

/** Sahte/zehirli DNS'e takılmamak için DoH ile çözümleme. */
object Doh {
    private val cache = HashMap<String, String>()

    fun resolve(host: String): String? {
        synchronized(cache) { cache[host]?.let { return it } }
        return try {
            val c = URL("https://1.1.1.1/dns-query?name=$host&type=A").openConnection() as HttpURLConnection
            c.setRequestProperty("accept", "application/dns-json")
            c.connectTimeout = 4000
            c.readTimeout = 4000
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("Answer")
            var ip: String? = null
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optInt("type") == 1) { ip = o.getString("data"); break }
            }
            if (ip != null) synchronized(cache) { cache[host] = ip }
            ip
        } catch (e: Exception) {
            null
        }
    }
}

/** Her stratejiyi Discord'un gerçek alan adlarına karşı dener, çalışanı bulur. */
object Tester {
    private val hosts = listOf(
        "discord.com", "gateway.discord.gg", "cdn.discordapp.com",
        "media.discordapp.net", "discordapp.com",
    )

    private fun probe(host: String): Boolean {
        val ip = Doh.resolve(host) ?: return false
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", Byedpi.PORT))
            Socket(proxy).use { raw ->
                raw.connect(InetSocketAddress(ip, 443), 5000)
                raw.soTimeout = 5000
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, 443, true) as SSLSocket
                ssl.startHandshake()
                ssl.outputStream.apply {
                    write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                ssl.inputStream.bufferedReader().readLine()?.startsWith("HTTP/") == true
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun testAll(): Boolean = coroutineScope {
        hosts.map { h -> async(Dispatchers.IO) { probe(h) } }.awaitAll().all { it }
    }

    suspend fun autoTune(ctx: Context, onProgress: (Int, Int, Preset) -> Unit): Preset? =
        withContext(Dispatchers.IO) {
            for ((i, p) in Presets.all.withIndex()) {
                onProgress(i + 1, Presets.all.size, p)
                if (!Byedpi.start(ctx, p.args)) { Byedpi.stop(); continue }
                val ok = testAll()
                Byedpi.stop()
                if (ok) return@withContext p
            }
            null
        }
}
