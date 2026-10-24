package com.dpibypass

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Yedek tünel motoru. Native kütüphane (hev) çökerse devreye girer.
 * TUN'dan gelen IPv4 TCP bağlantılarını ByeDPI SOCKS5'ine aktarır, DNS sorgularını DoH ile çözer.
 */
class KotlinTun(private val pfd: ParcelFileDescriptor, private val socksPort: Int) {
    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)
    private val outLock = Any()
    private val conns = ConcurrentHashMap<String, Conn>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private val mask = 0xFFFFFFFFL

    fun start() {
        running = true
        Thread { loop() }.apply { isDaemon = true; start() }
        Thread {
            while (running) {
                try { Thread.sleep(30000) } catch (_: Exception) {}
                val now = System.currentTimeMillis()
                for (c in conns.values) if (now - c.last > 180000) c.close()
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        for (c in conns.values) c.close()
        conns.clear()
        pool.shutdownNow()
        // Not: tun dosya tanıtıcısı servis tarafından kapatılır (burada kapatmıyoruz).
    }

    // ---------- yardımcılar ----------
    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
            (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v shr 24).toByte(); b[i + 1] = (v shr 16).toByte()
        b[i + 2] = (v shr 8).toByte(); b[i + 3] = v.toByte()
    }

    private fun csum(b: ByteArray, off: Int, len: Int, init: Long): Int {
        var sum = init
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += ((u8(b, i) shl 8) or u8(b, i + 1)).toLong(); i += 2 }
        if (i < end) sum += (u8(b, i) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun le(a: Long, b: Long): Boolean = ((b - a) and mask) < 0x80000000L

    private fun readFully(i: InputStream, b: ByteArray, n: Int) {
        var r = 0
        while (r < n) {
            val k = i.read(b, r, n - r)
            if (k < 0) throw IOException("eof")
            r += k
        }
    }

    private fun writeTun(pkt: ByteArray) {
        synchronized(outLock) {
            try { output.write(pkt) } catch (_: Exception) {}
        }
    }

    // ---------- ana döngü ----------
    private fun loop() {
        val buf = ByteArray(32768)
        while (running) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n < 0) break
            if (n == 0) { try { Thread.sleep(5) } catch (_: Exception) {}; continue }
            try { handle(buf, n) } catch (e: Exception) { AppLog.w("paket hatası: ${e.message}") }
        }
    }

    private fun handle(p: ByteArray, len: Int) {
        if (len < 20) return
        if ((u8(p, 0) shr 4) != 4) return // IPv6 paketleri bilerek düşürülür
        val ihl = (u8(p, 0) and 0xF) * 4
        val total = u16(p, 2)
        if (total > len || total < ihl) return
        when (u8(p, 9)) {
            6 -> tcp(p, ihl, total)
            17 -> udp(p, ihl, total)
        }
    }

    // ---------- UDP: sadece DNS ----------
    private fun udp(p: ByteArray, ihl: Int, total: Int) {
        if (total < ihl + 8) return
        val sPort = u16(p, ihl)
        val dPort = u16(p, ihl + 2)
        if (dPort != 53) return
        val q = p.copyOfRange(ihl + 8, total)
        val cIp = p.copyOfRange(12, 16)
        val sIp = p.copyOfRange(16, 20)
        pool.execute {
            try {
                val resp = doh(q)
                if (resp != null) writeTun(buildUdp(sIp, dPort, cIp, sPort, resp))
            } catch (_: Exception) {}
        }
    }

    private fun doh(query: ByteArray): ByteArray? {
        return try {
            val c = URL("https://1.1.1.1/dns-query").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("content-type", "application/dns-message")
            c.setRequestProperty("accept", "application/dns-message")
            c.outputStream.use { it.write(query) }
            c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildUdp(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, data: ByteArray): ByteArray {
        val total = 28 + data.size
        val b = ByteArray(total)
        b[0] = 0x45; put16(b, 2, total); b[6] = 0x40; b[8] = 64; b[9] = 17
        System.arraycopy(srcIp, 0, b, 12, 4)
        System.arraycopy(dstIp, 0, b, 16, 4)
        put16(b, 10, csum(b, 0, 20, 0L))
        put16(b, 20, srcPort); put16(b, 22, dstPort); put16(b, 24, 8 + data.size)
        System.arraycopy(data, 0, b, 28, data.size)
        return b
    }

    // ---------- TCP ----------
    private fun tcp(p: ByteArray, ihl: Int, total: Int) {
        if (total < ihl + 20) return
        val sPort = u16(p, ihl)
        val dPort = u16(p, ihl + 2)
        val seq = u32(p, ihl + 4)
        val ack = u32(p, ihl + 8)
        val off = (u8(p, ihl + 12) shr 4) * 4
        val flags = u8(p, ihl + 13)
        val win = u16(p, ihl + 14)
        val dataStart = ihl + off
        if (off < 20 || dataStart > total) return
        val len = total - dataStart
        val key = "${u32(p, 12)}:$sPort>${u32(p, 16)}:$dPort"
        val syn = (flags and 2) != 0
        val ackF = (flags and 16) != 0
        val rst = (flags and 4) != 0

        if (syn && !ackF) {
            if (conns.containsKey(key)) return
            var ws = 0
            var i = ihl + 20
            while (i < dataStart) {
                val k = u8(p, i)
                if (k == 0) break
                if (k == 1) { i++; continue }
                if (i + 1 >= dataStart) break
                val l = u8(p, i + 1)
                if (l < 2) break
                if (k == 3 && l == 3 && i + 2 < dataStart) ws = min(u8(p, i + 2), 14)
                i += l
            }
            val c = Conn(p.copyOfRange(12, 16), sPort, p.copyOfRange(16, 20), dPort, seq, ws, key)
            conns[key] = c
            pool.execute { c.connect() }
            return
        }

        val c = conns[key]
        if (c == null) {
            if (!rst) {
                val t = Conn(p.copyOfRange(12, 16), sPort, p.copyOfRange(16, 20), dPort, seq, 0, key)
                if (ackF) t.rawSend(4, ack, 0L)
                else t.rawSend(20, 0L, (seq + len + (if (syn) 1 else 0)) and mask)
            }
            return
        }
        if (rst) { c.close(); return }
        c.onSegment(flags, seq, ack, win, p, dataStart, len)
    }

    private fun buildTcp(c: Conn, flags: Int, seq: Long, ack: Long, data: ByteArray?, n: Int, mss: Boolean): ByteArray {
        val th = if (mss) 24 else 20
        val total = 20 + th + n
        val b = ByteArray(total)
        b[0] = 0x45; put16(b, 2, total); b[6] = 0x40; b[8] = 64; b[9] = 6
        System.arraycopy(c.sIp, 0, b, 12, 4)
        System.arraycopy(c.cIp, 0, b, 16, 4)
        put16(b, 10, csum(b, 0, 20, 0L))
        put16(b, 20, c.sPort); put16(b, 22, c.cPort)
        put32(b, 24, seq); put32(b, 28, ack)
        b[32] = ((th / 4) shl 4).toByte(); b[33] = flags.toByte()
        put16(b, 34, 65535)
        if (mss) { b[40] = 2; b[41] = 4; put16(b, 42, 1400) }
        if (data != null && n > 0) System.arraycopy(data, 0, b, 20 + th, n)
        val pseudo = (u16(b, 12) + u16(b, 14) + u16(b, 16) + u16(b, 18) + 6 + th + n).toLong()
        put16(b, 36, csum(b, 20, th + n, pseudo))
        return b
    }

    private inner class Conn(
        val cIp: ByteArray, val cPort: Int,
        val sIp: ByteArray, val sPort: Int,
        cIsn: Long, val wscale: Int, val key: String
    ) {
        private val lock = ReentrantLock()
        private val cond = lock.newCondition()
        @Volatile var rcvNxt: Long = (cIsn + 1) and mask
        private val isn: Long = System.nanoTime() and 0x7FFFFFFFL
        private var sndNxt: Long = isn
        private var sndUna: Long = isn
        private var peerWin: Int = 65535
        @Volatile var sock: Socket? = null
        @Volatile var ready = false
        @Volatile var closed = false
        @Volatile var last = System.currentTimeMillis()
        private var finSent = false
        private var finRcvd = false
        private val wq = LinkedBlockingQueue<ByteArray>()

        fun rawSend(flags: Int, seq: Long, ack: Long) {
            writeTun(buildTcp(this, flags, seq, ack, null, 0, false))
        }

        fun sendSeg(flags: Int, data: ByteArray?, n: Int) {
            lock.withLock {
                val pkt = buildTcp(this, flags, sndNxt, rcvNxt, data, n, false)
                if (data != null) sndNxt = (sndNxt + n) and mask
                if ((flags and 3) != 0) sndNxt = (sndNxt + 1) and mask
                writeTun(pkt)
            }
        }

        fun connect() {
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", socksPort), 5000)
                s.tcpNoDelay = true
                val o = s.getOutputStream()
                val i = s.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                val r = ByteArray(2); readFully(i, r, 2)
                if (r[1].toInt() != 0) throw IOException("socks yetki")
                val req = ByteArray(10)
                req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 1
                System.arraycopy(sIp, 0, req, 4, 4)
                req[8] = (sPort shr 8).toByte(); req[9] = sPort.toByte()
                o.write(req); o.flush()
                val h = ByteArray(4); readFully(i, h, 4)
                if (h[1].toInt() != 0) throw IOException("socks yanıt ${h[1]}")
                when (h[3].toInt()) {
                    1 -> readFully(i, ByteArray(6), 6)
                    4 -> readFully(i, ByteArray(18), 18)
                    3 -> {
                        val l = ByteArray(1); readFully(i, l, 1)
                        val n = (l[0].toInt() and 0xFF) + 2
                        readFully(i, ByteArray(n), n)
                    }
                }
                sock = s
                // SYN-ACK
                writeTun(buildTcp(this, 18, isn, rcvNxt, null, 0, true))
                lock.withLock { sndNxt = (isn + 1) and mask; sndUna = sndNxt }
                pool.execute { writerLoop(s) }
                ready = true
                pump(i)
            } catch (e: Exception) {
                if (!closed) {
                    if (!ready) rawSend(20, 0L, rcvNxt) else reset()
                }
                close()
            }
        }

        private fun writerLoop(s: Socket) {
            try {
                val o = s.getOutputStream()
                while (true) {
                    val d = wq.take()
                    if (d.isEmpty()) {
                        if (!closed) try { s.shutdownOutput() } catch (_: Exception) {}
                        break
                    }
                    o.write(d); o.flush()
                }
            } catch (e: Exception) {
                if (!closed) reset()
            }
        }

        private fun pump(i: InputStream) {
            val buf = ByteArray(1400)
            while (!closed) {
                var room = 0
                lock.withLock {
                    while (!closed) {
                        room = peerWin - ((sndNxt - sndUna) and mask).toInt()
                        if (room > 0) break
                        cond.await(200, TimeUnit.MILLISECONDS)
                    }
                }
                if (closed) break
                val n = i.read(buf, 0, min(1400, room))
                if (n < 0) break
                last = System.currentTimeMillis()
                sendSeg(24, buf, n)
            }
            if (!closed) {
                lock.withLock {
                    if (!finSent) { finSent = true; sendSeg(17, null, 0) }
                }
                checkDone()
            }
        }

        fun onSegment(flags: Int, seq: Long, ackNum: Long, win: Int, p: ByteArray, off: Int, len: Int) {
            last = System.currentTimeMillis()
            if (!ready) return
            lock.withLock {
                if ((flags and 16) != 0) {
                    if (le(sndUna, ackNum) && le(ackNum, sndNxt)) sndUna = ackNum
                    peerWin = win shl wscale
                    cond.signalAll()
                }
            }
            var needAck = false
            if (len > 0) {
                if (seq == rcvNxt) {
                    wq.offer(p.copyOfRange(off, off + len))
                    rcvNxt = (rcvNxt + len) and mask
                }
                needAck = true
            }
            if ((flags and 1) != 0 && ((seq + len) and mask) == rcvNxt && !finRcvd) {
                finRcvd = true
                rcvNxt = (rcvNxt + 1) and mask
                wq.offer(ByteArray(0))
                needAck = true
            }
            if (needAck) sendSeg(16, null, 0)
            checkDone()
        }

        private fun checkDone() {
            val done = lock.withLock { finSent && finRcvd && sndUna == sndNxt }
            if (done) close()
        }

        fun reset() {
            try { sendSeg(20, null, 0) } catch (_: Exception) {}
            close()
        }

        fun close() {
            if (closed) return
            closed = true
            conns.remove(key)
            try { sock?.close() } catch (_: Exception) {}
            wq.offer(ByteArray(0))
            lock.withLock { cond.signalAll() }
        }
    }
}
