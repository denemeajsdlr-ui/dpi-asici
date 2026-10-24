package hev.htproxy

/** hev-socks5-tunnel JNI köprüsü. Paket/sınıf adı native tarafla birebir eşleşmeli. */
object TProxyService {
    init { System.loadLibrary("hev-socks5-tunnel") }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
    @JvmStatic external fun TProxyStopService()
    @JvmStatic external fun TProxyGetStats(): LongArray
}
