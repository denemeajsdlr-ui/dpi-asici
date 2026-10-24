# DPI Aşıcı (Android)

GoodbyeDPI mantığıyla çalışan, telefonda yerel VPN kuran DPI aşma uygulaması.

Yapı: Android VpnService → hev-socks5-tunnel (IP paketlerini SOCKS5'e çevirir) → ByeDPI (TLS paketini parçalar/bozar) → internet.
Uygulama açılışta Discord'a karşı yöntemleri deneyip çalışanı seçebilir ("Otomatik bul").

## Derleme yolu A: GitHub ile (Android Studio gerekmez)
1. Bu klasörü bir GitHub deposuna yükle.
2. Actions sekmesi > "APK Derle" > Run workflow.
3. Bitince "DpiBypass-apk" çıktısını indir, içindeki app-debug.apk'yi telefona kur.

## Derleme yolu B: Bilgisayarda
1. Android Studio + NDK 26.3.11579264 kur.
2. `bash scripts/setup.sh` (Linux/macOS/WSL/Git Bash)
3. Projeyi Android Studio ile aç, Run.

## Bilinen sınırlar
- Hiçbir DPI yöntemi her operatörde çalışmaz. Çalışmazsa tünel (WireGuard) modu gerekir.
- İnternet tamamen kesilirse: "Yöntem" olarak başka bir seçenek dene.
