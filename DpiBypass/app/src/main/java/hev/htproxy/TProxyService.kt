package hev.htproxy

/** hev-socks5-tunnel JNI köprüsü. Paket/sınıf adı native tarafla birebir eşleşmeli. */
class TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray?

    companion object {
        init { System.loadLibrary("hev-socks5-tunnel") }
    }
}
