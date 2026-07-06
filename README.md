# KoveMirror

(English version below | Türkçe versiyonu aşağıdadır)

---

# 🇹🇷 KoveMirror (Türkçe)

KoveMirror, Kove 800 (800X Pro vb.) model motosikletlerin TFT ekranlarına telefonunuzun ekranını yansıtmanızı (Screen Mirroring) sağlayan açık kaynaklı bir Android uygulamasıdır. Orijinal ThinkerRide sistemine alternatif olarak geliştirilmiş olup, tamamen yerel ağ üzerinden bağımsız çalışır. 

Motosikletin navigasyon için TFT ekranında gösterdiği görüntüyü, herhangi bir üçüncü taraf uygulamaya bağımlı kalmadan Google Maps, Yandex Navigasyon gibi kendi istediğiniz uygulamalarla kullanabilmenizi sağlar.

## Protokol Yapısı ve İletişim

Kove TFT ekranları, telefon ile haberleşmek için karmaşık bir Bluetooth (BLE) ve Wi-Fi (TCP) altyapısı kullanır. Haberleşme mimarisi şu adımlardan oluşur:

### 1. Wi-Fi Ağı (Network)
Motosiklet, kendi üzerinde bir Wi-Fi Hotspot oluşturur (Genellikle `192.168.10.1` IP adresi). Telefon, bu ağa bağlandığında IP adresi alır (Örn: `192.168.10.2`). Uygulama `bindProcessToNetwork` kullanarak telefonun mobil verisi açık olsa bile KoveMirror'un sadece motosikletin ağı üzerinden iletişim kurmasını garanti eder. Bu sayede telefondaki diğer uygulamalar arka planda mobil veri üzerinden internete bağlanmaya devam edebilir (Bunun için telefonunuzun Wi-Fi ayarlarından "İnternetsiz ağlarda mobil veriyi kullan" seçeneğinin açık olması gerekir).

### 2. TCP Portları
Telefon (KoveMirror uygulaması), motosikletin bağlanması için 3 farklı TCP sunucu soketi (Server Socket) açar:
- **Port 17818 (Control Port):** İki cihaz arasındaki el sıkışma (handshake), versiyon bilgisi aktarımı ve sensör / araç durumu için kullanılır.
- **Port 15456 (Video Port):** `MediaProjection` API'den alınan ve H.264 formatında encode edilen ekran videosunun TFT'ye aktarıldığı ana porttur.
- **Port 15457 (Dedicated Heartbeat Port):** Bağlantının koptuğunu anında tespit etmek için sürekli olarak (200ms aralıklarla) ping/kalp atışı gönderilen sokettir.

### 3. Bluetooth (BLE) Eşleşmesi ve Handshake
Cihazların haberleşmeye başlaması için BLE zorunludur:
- Uygulama `0000e0ff-...` Service UUID değerine bağlanır.
- `FFE1` karakteristik adresine yazma (Write) işlemi yapar, `FFE2` üzerinden bildirimleri (Notify) dinler.
- Telefondan `PAIR` komutu (msg_id: 27) gönderilir. TFT `result: 1` yanıtını verdikten sonra, uygulama yansıtmayı aktifleştiren (`msg_id: 25, msg_type: 23`) JSON paketini gönderir.

### 4. Control Handshake & Heartbeat
- BLE üzerinden yansıtma aktif edildikten sonra TFT, telefonun 17818 portuna bağlanır.
- TFT tarafından gönderilen `TUC` (Token) paketi alınır.
- Binary (Hex) formatında karşılıklı komutlar gönderilir.
- **2026 Modeller İçin Önemli:** 2026 model Kove TFT ekranları güvenlik amacıyla her 1 saniyede bir Control Portu üzerinden `02 01 00 00 00 00` şeklinde Heartbeat gönderir. Uygulamanın Video soketini açması için bu paketi "yankılayarak (echo)" TFT'ye geri göndermesi şarttır.

## Nasıl Çalışır?

1. Telefon motosikletin Bluetooth ve Wi-Fi ağına bağlanır.
2. Uygulama açılıp "Start Mirroring" (Yansıtmayı Başlat) butonuna basılır.
3. KoveMirror, Android `MediaProjection` API'si ile ekran yakalama izni ister.
4. Eşzamanlı olarak arka planda 3 farklı TCP sunucusu açılır ve BLE cihazı taranır.
5. TFT ile BLE eşleşmesi tamamlanır.
6. Android'in sanal ekranından (VirtualDisplay) gelen ham görüntüler `MediaCodec` kullanılarak donanımsal olarak H.264 formatına çevrilir (600x1024 çözünürlükte).
7. H.264 byteları, özel bir header (genişlik/yükseklik bilgisi içeren) ile birlikte Video Portu üzerinden TFT'ye kesintisiz olarak aktarılır.
8. Arka planda saniyede birden fazla Heartbeat gönderilerek bağlantının canlı tutulması sağlanır.

*Not: Uygulama ön plandayken telefon ekranının uykuya geçmemesi için `FLAG_KEEP_SCREEN_ON` aktiftir. Ekran kapanırsa Android tasarruf amacıyla GPU render işlemini durdurduğundan TFT ekranında görüntü donacaktır.*

## Derleme Gereksinimleri (Build Instructions)

Bu projeyi derleyebilmek için:
- **Android Studio** (Güncel Sürüm önerilir)
- **JDK 17** veya üstü
- **Android SDK:** `compileSdk 34`
- **Gradle:** Sürüm `8.x` ve üstü

Projeyi Android Studio'da açıp `Build -> Make Project` diyerek ya da komut satırından `./gradlew assembleDebug` komutuyla doğrudan derleyebilirsiniz.

## Test Edilen Cihazlar
- **2024 Kove 800X Pro:** Sorunsuz çalışıyor.
- **2026 Kove Modelleri:** Gelişmiş güvenlik ve strict heartbeat protokolleriyle başarılı şekilde test edildi.

---

# 🇬🇧 KoveMirror (English)

KoveMirror is an open-source Android application that enables screen mirroring from your smartphone directly to the TFT dashboard of Kove 800 motorcycles (like the 800X Pro). Built as an alternative to the official ThinkerRide system, it operates entirely locally over the motorcycle's network.

This allows you to project any app of your choice (such as Google Maps, Waze, or Spotify) onto your motorcycle’s screen without being tied to restrictive third-party navigation apps.

## Protocol Structure and Communication

Kove TFT screens use a complex combination of Bluetooth (BLE) and Wi-Fi (TCP) to negotiate and stream the display. The architecture consists of the following components:

### 1. Wi-Fi Network
The motorcycle broadcasts its own Wi-Fi Hotspot (typically with the IP `192.168.10.1`). When the phone connects, it is assigned a local IP (e.g., `192.168.10.2`). The app uses Android's `bindProcessToNetwork` API to force all application traffic through the motorcycle's Wi-Fi, allowing the phone to maintain internet connectivity over Mobile Data for other background applications (ensure your phone's "Use mobile data when Wi-Fi has no internet" setting is enabled).

### 2. TCP Ports
The phone acts as the Server, opening three distinct TCP server sockets that the motorcycle connects to:
- **Port 17818 (Control Port):** Used for initial binary handshakes, version exchange, and receiving continuous vehicle telemetry (like tire pressure and fuel levels).
- **Port 15456 (Video Port):** The main high-bandwidth socket where the H.264 encoded screen video stream is sent to the TFT.
- **Port 15457 (Dedicated Heartbeat Port):** A fast-ping socket used to rapidly detect connection drops (pings sent every 200ms).

### 3. Bluetooth (BLE) Handshake
BLE negotiation is strictly required before the TFT will accept video connections:
- The app connects to the `0000e0ff-...` Service UUID.
- It writes to the `FFE1` characteristic and subscribes to notifications on `FFE2`.
- A `PAIR` command (msg_id: 27) is sent. Once the TFT responds with `result: 1`, the app triggers the mirror activation command (`msg_id: 25, msg_type: 23`).

### 4. Control Handshake & Heartbeat
- Following BLE activation, the TFT connects to the phone's 17818 Control port.
- The TFT sends a `TUC` (Token) packet.
- Binary (Hex) handshakes are exchanged.
- **Critical for 2026 Models:** For newer Kove models (2026+), the TFT implements a stricter security protocol by continuously sending a 6-byte heartbeat (`02 01 00 00 00 00`) over the Control Port every second. The app must echo this exact heartbeat back immediately; otherwise, the TFT will refuse to open the Video Port.

## How It Works

1. The user connects their phone to the motorcycle's Wi-Fi and Bluetooth.
2. The user launches the app and taps "Start Mirroring".
3. KoveMirror requests screen capture permissions via the Android `MediaProjection` API.
4. Concurrently, the three TCP servers start listening, and the BLE connection is established.
5. The BLE pairing and TCP Control handshakes complete successfully.
6. A `VirtualDisplay` captures the raw screen frames, which are hardware-encoded into an H.264 bitstream (at 600x1024 resolution) using `MediaCodec`.
7. The raw H.264 bytes are prepended with a custom resolution header and streamed continuously over the Video Port.
8. Background threads manage the dedicated TCP heartbeats and BLE keep-alives to prevent the TFT from terminating the stream.

*Note: The app enables `FLAG_KEEP_SCREEN_ON` to prevent the phone from sleeping while the app is in the foreground. If the physical phone screen is turned off, Android halts GPU rendering, which will cause the TFT stream to freeze.*

## Build Instructions

To compile this project, you will need:
- **Android Studio** (Latest stable version recommended)
- **JDK 17** or higher
- **Android SDK:** `compileSdk 34`
- **Gradle:** Version `8.x` or higher

Open the project in Android Studio and select `Build -> Make Project`, or build directly from the terminal using `./gradlew assembleDebug`.

## Tested Devices
- **2024 Kove 800X Pro:** Fully compatible.
- **2026 Kove Models:** Fully compatible (incorporates strict heartbeat echoing and synchronized BLE pair timing).
