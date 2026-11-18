# KoveMirror

# Latest APK is in builds directory. "KoveMirrorLatest.apk", right click and then select save as to download.

# 🇹🇷 KoveMirror (Türkçe)

(Bu metin ve program AI tarafından geliştirilmiştir.)

KoveMirror, Kove 800 (800X Pro vb.) model motosikletlerin TFT ekranlarına telefonunuzun ekranını yansıtmanızı (Screen Mirroring) sağlayan açık kaynaklı bir Android uygulamasıdır. Orijinal ThinkerRide sistemine alternatif olarak geliştirilmiş olup, tamamen yerel ağ üzerinden bağımsız çalışır.

Motosikletin navigasyon için TFT ekranında gösterdiği görüntüyü, herhangi bir üçüncü taraf uygulamaya bağımlı kalmadan Google Maps, Yandex Navigasyon, Kurviger gibi kendi istediğiniz uygulamalarla kullanabilmenizi sağlar. Ayrıca uygulama içinde motosiklet sürüşü için optimize edilmiş yerleşik **Android Auto Desteği, 2D/3D & Çevrimdışı Harita (`.map`), Rota Üstü Çevrimdışı Benzin İstasyonları (`.poi`), Rota Import (GPX/KML/KMZ), Canlı Rota Hava Durumu, Sert Viraj İkazları, Adım Adım Navigasyon, Gün Doğumu/Batımı – Pusula – Rakım Telemetri Kartı ve Ekran Kapalıyken Kesintisiz TFT Haritası** özellikleri bulunur.

<p align="center">
  <img src="screenshots/main_screen.png" width="32%" alt="KoveMirror Ana Ekran" />
  &nbsp; &nbsp; &nbsp; &nbsp;
  <img src="screenshots/map_screen.png" width="32%" alt="Harita ve Rota Ekranı" />
</p>

---

## 🗺️ Harita, Rota ve Navigasyon Özellikleri

Uygulama içinde **"Harita & Rota"** butonu ile erişilebilen gelişmiş harita modülü şu özellikleri sunar:

1. **Çoklu Harita Katmanları (2D, 3D & Çevrimdışı MapsForge)**:
   - **Harita (OSM):** OpenStreetMap Mapnik standart harita katmanı.
   - **Topo:** OpenTopoMap arazi ve topografya haritası.
   - **Uydu (Sat):** ESRI World Imagery yüksek çözünürlüklü uydu görüntüsü katmanı.
   - **Google Katmanları:** Google Maps, Google Satellite ve Google Hybrid katmanları.
   - **Çevrimdışı Vektör Harita (`.map`):** OpenAndroMaps / MapsForge `.map` dosyalarını internet olmadan doğrudan açabilme ve harita etiketi ölçekleme (%100 – %200).
   - **🌐 3D Vektör & Arazi Haritası (MapLibre):** 3 boyutlu bina, arazi ve sürüş perspektifi sunan modern 3D harita katmanı. Gidon tuşu veya ekrandan tek tıkla 2D/3D arasında anında geçiş.

2. **🚗 Android Auto Desteği (`androidx.car.app`)**:
   - KoveMirror Harita ve Navigasyon modülü doğrudan **Android Auto** uyumlu araç/motosiklet ekranlarında da çalışır (`NavigationTemplate`).
   - **Bağımsız & Çift Yönlü Senkron:** Telefonda *Harita & Rota* ekranı kapalı olsa bile GPS, adım adım dönüş yönlendirmesi (Valhalla / OSRM), mesafe/ETA bilgisi, harita katmanları ve kayıtlı rotalar Android Auto'da çalışır; telefon ekranı açıldığında durumlar iki taraf arasında anlık senkronize olur.
   - **Ekran Üstü Kontroller:** Android Auto üzerinden parmakla sürükleme (harita açısına göre uyumlu kaydırma), yakınlaştırma/uzaklaştırma (`+`/`−`), konuma odaklama, harita katmanı değiştirme, **⛽ Benzin İstasyonları** ve **🌤️ Hava Durumu** katmanlarını açıp kapatma.
   - **Rotalar & Favoriler Menüsü:** Kayıtlı GPX/KML rotalarını haritada açıp kapatma ve favori konumlara tek dokunuşla adım adım navigasyon başlatma.
   - **Okunaklı Araç Ekranı Ölçeklemesi & Telemetri:** Araç ekranı DPI değerine göre otomatik büyütülen rota rozetleri, km işaretleri, yön okları ve sağ alt köşede hız, katman, pusula, rakım ile gün doğumu/batımı kartı.
   - *APK Kurulum Notu:* Uygulama doğrudan APK ile yüklendiyse telefonunuzda **Android Auto > Ayarlar > Sürüm** alanına art arda dokunarak **Geliştirici Modu > Bilinmeyen Kaynaklar** seçeneğini aktif etmeniz gerekir.

3. **📱 Ekran Kapalıyken Kesintisiz TFT Navigasyonu (Virtual Display Presentation)**:
   - Telefon ekranı kapandığında / kilitlendiğinde bile arka planda çalışan sanal ekran mimarisi (`VirtualDisplay` & `KovePresentation`) sayesinde motosikletin TFT kadranına kesintisiz harita, canlı GPS takibi, hız/pusula/rakım telemetrisi, yüklü rotalar ve adım adım navigasyon akmaya devam eder.
   - Telefonu tekrar açtığınızda telefon ile TFT harita durumları kusursuz olarak çift yönlü senkronize olur.

4. **⛽ Rota Üstü Çevrimdışı Benzin İstasyonları (`.poi`)**:
   - OpenAndroMaps `.poi` SQLite veritabanını kullanarak aktif navigasyon veya yüklü GPX/KML rotaları üzerindeki benzin istasyonlarını %100 çevrimdışı olarak bulur.
   - Uzun basarak koridor genişliği seçilebilir (`500 m`, `1 km`, `2 km`, `5 km`, `10 km`, `50 km`).
   - Harita üzerinde marka ve rota mesafesi rozetleri gösterir; istasyona dokunarak açılış saati/sapma mesafesi detaylarını görebilir ve tek tıkla navigasyon başlatabilirsiniz.

5. **🌤️ Canlı Rota Hava Durumu (Route Weather Forecast)**:
   - Rota güzergahı boyunca açık kaynaklı Open-Meteo entegrasyonu ile sıcaklık (°C), yağış ihtimali (%), yağış miktarı (mm) ve rüzgar hızı rozetleri harita üzerinde gösterilir.
   - Rozetler 2D, 3D, Kove TFT ve Android Auto haritalarında rota çizgisinin üstünde net okunur şekilde konumlanır; telefondan dokunulduğunda detaylı hava durumu bilgisi verir.

6. **⚡ Sert Viraj & U-Dönüş Uyarı Sistemi (Sharp Curve & Hairpin Warnings)**:
   - %100 çevrimdışı geometrik analiz: İnternet gerektirmeden rota geometrisindeki açı değişimleri ve viraj yarıçapını anında analiz eder.
   - Harita üzerinde viraj başlarında renk kodlu kompakt rozetler gösterir:
     - 🔴 **Kırmızı (`⚠️`):** U-Viraj / Hairpin ($180^\circ$ dönüşler / İğne deliği)
     - 🟠 **Turuncu (`⬅️` / `➡️`):** Sert Viraj ($90^\circ$ ve üzeri keskin virajlar)
     - 🟡 **Sarı (`↖️` / `↗️`):** Orta Sertlikte Viraj

7. **KML / KMZ / GPX Rota Import & Takip**:
   - GPX, KML ve KMZ formatındaki sürüş rotalarını ve KML yer işaretlerini/poligonlarını harita üzerine yükleme.
   - Renk ve çizgi kalınlığı ile özelleştirme, yön okları ve km mesafe taşları ekleme.
   - Birden fazla rotayı aynı anda grup halinde yönetme, görünürlüklerini açıp kapama, yönünü tersine çevirme (Reverse Route) veya silme.

8. **📍 Valhalla & OSRM Adım Adım Navigasyon + Favoriler / Adres Arama**:
   - Harita üzerinde herhangi bir yere **uzun basarak (long press)** veya **Favoriler / Adres Arama (`⭐`)** üzerinden hedef belirleyip navigasyon başlatma.
   - **Rota Tercihleri & Otomatik Yeniden Hesaplama:** Ücretli yollardan kaçınma, otoyoldan kaçınma, en kısa rota seçenekleri ve rotadan sapıldığında otomatik yeniden rota hesaplama.
   - **Canlı Turn Banner:** Sıradaki dönüş yönü, manevraya kalan mesafe ve toplam kalan süre/km takibi.

9. **🏎️ Gelişmiş Telemetri HUD (Hız, Hız Limiti, Pusula, Rakım, Gün Doğumu & Batımı)**:
   - Anlık GPS hız göstergesi ve OpenStreetMap / Overpass verisiyle yasal hız sınırı tespiti (hız aşıldığında kırmızı görsel ikaz).
   - Hem telefon harita ekranında hem Android Auto ekranında anlık **Pusula (`🧭`)**, **Rakım (`⛰️ m`)** ve %100 çevrimdışı astronomik **Gün Doğumu / Gün Batımı (`🌅 / 🌇`)** saatleri.

10. **🔄 Akıllı Dikey/Yatay Ekran Modu & Otomatik Gizlenen Üst Bar**:
    - **Dinamik Yön Kilidi:** Kove TFT ekran yansıtması aktifken telefon otomatik olarak dikey (`Portrait`) modda kilitlenir; yansıtma kapalıyken telefon serbestçe yatay (`Landscape`) ve dikey dönebilir.
    - **Yatay Moda Uyumlu Sağ Kontrol Paneli:** Dikey modda tek sütun, yatay modda ise tüm butonlara kolayca erişilebilmesi için otomatik 2 sütunlu ve kaydırılabilir (`ScrollView`) yerleşim.
    - **Otomatik Gizlenen Üst Bar:** Harita ekranındaki üst bar 5 saniye işlem yapılmadığında otomatik gizlenerek harita alanını büyütür; ekrana tekrar dokunulduğunda geri gelir.

11. **🔴 GPX Canlı Rota Kaydedici & Profesyonel Harita Ayarları**:
    - Sürüşünüzü harita üzerinden takip ederken gerçek zamanlı olarak GPX formatında cihazınıza kaydetme.
    - **Canlı Önizleme Kutusu:** Gündüz / Gece / Otomatik harita temasını, 5 farklı konum imlecini (`⬆️ Ok`, `🏍️ Motor`, `🔵 Nokta`, `🎯 Hedef`, `📍 İğne`), 7 renkli paleti ve iz rengini anında simüle eden ayarlar penceresi.

---

## ⚡ Sistem ve Gelişmiş Özellikler

- **🔋 Ekran Karartma / Enerji Tasarrufu Modu (Dimming):** Yansıtma açıkken telefonun aşırı ısınmasını engellemek ve pil tüketimini düşürmek amacıyla telefon ekranını karartma seçeneği.
- **🎮 Gidon Kumandası Entegrasyonu & Tek Dokunuşla Yeniden Başlatma:** Motosikletin gidon tuşları ile harita kontrolü ve yayın koptuğunda **[ENT]** tuşuyla elinizi gidondan çekmeden tek dokunuşla yayını anında yeniden başlatma (`One-Touch Relaunch`).
- **📱 İkinci Ekrana (TFT) Uygulama Yansıtma:** `ActivityOptions.setLaunchDisplayId` ile telefonun ana ekranını serbest bırakırken Kove TFT Sanal Ekranına doğrudan **DMD2**, **OsmAnd** veya harici navigasyon uygulamalarını yansıtma.
- **🌐 6 Dil Desteği:** 🇹🇷 Türkçe, 🇬🇧 İngilizce, 🇪🇸 İspanyolca, 🇮🇹 İtalyanca, 🇬🇷 Yunanca ve 🇫🇷 Fransızca arayüz desteği.
- **🛡️ Gelişmiş Kararlılık & BLE MTU 512 Müzakeresi:** Yeni gösterge yazılımları (SV=2.0.4) için BLE MTU 512 müzakeresi, MediaProjection token önbelleğe alma, BLE komut kuyruğu yönetimi ve optimize edilmiş TCP soket yönetimi *(Dominik Ladoš'un katkılarıyla)*.

---

## Protokol Yapısı ve İletişim

Kove TFT ekranları, telefon ile haberleşmek için Bluetooth (BLE) ve Wi-Fi (TCP) altyapısı kullanır:

### 1. Wi-Fi Ağı (Network)
Motosiklet, kendi üzerinde bir Wi-Fi Hotspot oluşturur (Genellikle `192.168.10.1` IP adresi). Telefon, bu ağa bağlandığında IP adresi alır (`192.168.10.2`). Uygulama `bindProcessToNetwork` kullanarak telefonun mobil verisi açık olsa bile KoveMirror'un sadece motosikletin ağı üzerinden iletişim kurmasını garanti eder. Bu sayede telefondaki diğer uygulamalar arka planda mobil veri üzerinden internete bağlanmaya devam edebilir (Telefonunuzun Wi-Fi ayarlarından "İnternetsiz ağlarda mobil veriyi kullan" seçeneğini açmanız önerilir).

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

(This text and the program itself developed by AI.)

KoveMirror is an open-source Android application that enables screen mirroring from your smartphone directly to the TFT dashboard of Kove 800 motorcycles (like the 800X Pro). Built as an alternative to the official ThinkerRide system, it operates entirely locally over the motorcycle's network.

This allows you to project any navigation or media app of your choice (such as Google Maps, Waze, Kurviger, or Spotify) onto your motorcycle’s screen without restrictions. It also includes an offline-friendly, motorcycle-optimized built-in **Android Auto Support, 2D/3D & Offline Maps (`.map`), Offline Route Fuel Stations (`.poi`), Route Import (GPX/KML/KMZ), Live Route Weather, Sharp Curve Warnings, Turn-by-Turn Navigation, Sunrise/Sunset – Compass – Altitude Telemetry HUD, and Screen-Off Seamless TFT Display** module.

<p align="center">
  <img src="screenshots/main_screen.png" width="32%" alt="KoveMirror Main Screen" />
  &nbsp; &nbsp; &nbsp; &nbsp;
  <img src="screenshots/map_screen.png" width="32%" alt="Map & Route Screen" />
</p>

---

## 🗺️ Map, Route & Navigation Features

Accessible via the **"Map & Route"** button on the main screen:

1. **Multi-Layer Maps (2D, 3D & Offline MapsForge)**:
   - **Maps (OSM):** OpenStreetMap Mapnik standard map layer.
   - **Topo:** OpenTopoMap terrain and topographic contours.
   - **Satellite (Sat):** ESRI World Imagery high-resolution satellite imagery.
   - **Google Layers:** Google Maps, Google Satellite, and Google Hybrid tile layers.
   - **Offline Vector Maps (`.map`):** Load OpenAndroMaps / MapsForge `.map` files completely offline with customizable map label scaling (100% – 200%).
   - **🌐 3D Vector & Terrain Map (MapLibre):** Dynamic 3D building, terrain elevation, and riding perspective layer. Switch instantly between 2D and 3D via handlebar buttons or on-screen toggle.

2. **🚗 Android Auto Integration (`androidx.car.app`)**:
   - Run KoveMirror's map and navigation module directly on **Android Auto** head units (`NavigationTemplate`).
   - **Standalone & Bi-Directional Sync:** GPS tracking, turn-by-turn navigation (Valhalla / OSRM), distance/ETA cards, map layers, and saved routes run even when the phone's *Map & Route* screen is closed, and stay seamlessly synchronized when you open it on the phone.
   - **On-Screen Controls:** Rotation-aware touch panning, zoom in/out (`+`/`−`), recenter on GPS, map layer cycling, and direct **⛽ Fuel Stations** and **🌤️ Route Weather** toggles right from the car display.
   - **Routes & Favorites Screen:** Toggle visibility of saved GPX/KML routes and launch turn-by-turn navigation to any Favorite location with a single tap.
   - **High-Legibility Scaling & Telemetry HUD:** Auto-scaled route badges, distance markers, direction arrows, and a safe-area HUD showing speed, layer, compass, altitude, and sunrise/sunset times.
   - *Sideload APK Note:* If installed outside Google Play, enable **Android Auto > Settings > Version** (tap repeatedly for Developer Mode) **> Unknown Sources**.

3. **📱 Screen-Off Seamless TFT Navigation (Virtual Display Presentation)**:
   - Even when your phone screen is turned off or locked, KoveMirror runs an independent background `VirtualDisplay` and `Presentation` map, streaming live GPS positioning, speed/compass/altitude telemetry, imported routes, and turn-by-turn navigation directly to the motorcycle's TFT screen.
   - Seamless, real-time bi-directional synchronization when you turn your phone screen back on.

4. **⛽ Offline Route Fuel Stations (`.poi`)**:
   - Scans local OpenAndroMaps `.poi` SQLite databases 100% offline to locate fuel stations along active navigation or loaded GPX/KML routes.
   - Long-press the fuel toggle to configure the search corridor (`500 m`, `1 km`, `2 km`, `5 km`, `10 km`, `50 km`).
   - Displays brand and distance-along-route badges on the map with tap-to-view opening hours/detour distance and one-tap navigation.

5. **🌤️ Live Route Weather Forecast**:
   - Integrates with the open-source Open-Meteo API to fetch temperature (°C), rain probability (%), precipitation amount (mm), and wind speed badges along your route.
   - Badges are layered cleanly across 2D, 3D, Kove TFT, and Android Auto maps with tap-for-details support on the phone.

6. **⚡ Sharp Curve & Hairpin Warnings**:
   - 100% offline geometric scan: Analyzes angular deflection and curve radius without internet connection.
   - Displays color-coded badges directly on curve apexes:
     - 🔴 **Red (`⚠️`):** Hairpin / U-Turn ($180^\circ$ sharp turns)
     - 🟠 **Orange (`⬅️` / `➡️`):** Sharp Curve ($90^\circ+$ angles)
     - 🟡 **Yellow (`↖️` / `↗️`):** Medium Curve

7. **KML / KMZ / GPX Route Import & Tracking**:
   - Import riding routes, KML placemarks, and polygons in GPX, KML, and KMZ formats.
   - Customize route lines with 10 preset colors, stroke widths, directional arrows, and kilometer distance markers.
   - Manage multiple imported routes grouped cleanly (visibility toggle, reverse route direction, or delete).

8. **📍 Valhalla & OSRM Turn-by-Turn Navigation + Favorites / Address Search**:
   - **Long press** anywhere on the map or pick from **Favorites / Address Search (`⭐`)** to drop a destination pin and start navigation.
   - **Route Options & Off-Route Rerouting:** Avoid Tolls, Avoid Highways, Shortest/Fastest route options, plus automatic route recalculation when going off-route.
   - **Live Top Turn Banner:** Displays upcoming turn direction arrows, distance to next maneuver, and remaining trip distance/ETA.

9. **🏎️ Telemetry HUD (Speed, Speed Limit, Compass, Altitude, Sunrise & Sunset)**:
   - Real-time GPS speedometer with automatic road speed limit detection using OpenStreetMap / Overpass data and overspeed visual alerts.
   - Live **Compass (`🧭`)**, **GPS Altitude (`⛰️ m`)**, and 100% offline NOAA/USNO astronomical **Sunrise / Sunset (`🌅 / 🌇`)** times displayed on both the phone map screen and Android Auto.

10. **🔄 Dynamic Portrait/Landscape Mode & Auto-Hide Top Bar**:
    - **Smart Orientation Lock:** Automatically locks to `Portrait` while Kove TFT screen mirroring is active, and unlocks free `Portrait / Landscape` rotation whenever mirroring is off.
    - **Landscape-Ready Side Controls:** Right-side map controls switch automatically from 1 column (portrait) to a 2-column scrollable (`ScrollView`) layout in landscape so every button remains easily reachable.
    - **Auto-Hide Top Bar:** The top toolbar automatically fades out after 5 seconds of inactivity for an unobstructed map view and reappears smoothly on touch.

11. **🔴 Live GPX Track Recorder & Redesigned Professional Map Settings**:
    - Record your real-time rides directly into standard GPX files saved on your device.
    - **Hero Live Preview:** Simulates Day/Night/Auto theme, 5 location cursor shapes (`⬆️ Arrow`, `🏍️ Moto`, `🔵 Dot`, `🎯 Target`, `📍 Pin`), 7-color palette, and GPX track line in real-time.

---

## ⚡ System & Advanced Features

- **🔋 Energy-Saving Screen Dimming:** Automatically dims or turns down phone screen brightness during mirroring to conserve battery and eliminate phone overheating.
- **🎮 Handlebar Button Integration & One-Touch Relaunch:** Multi-mode handlebar controls for Zooming, Panning, Media playback, Volume control, App switching, and instantaneous One-Touch stream relaunch (**[ENT]** key) if disconnected while riding.
- **📱 Secondary Display App Projection:** Multi-display projection using `ActivityOptions.setLaunchDisplayId` to project apps like **DMD2**, **OsmAnd**, or custom navigation tools directly onto the Kove 600×1024 Virtual Display while keeping the phone's primary screen free for Waze or standard phone usage.
- **🌐 6 Language Support:** Full UI localization for 🇹🇷 Turkish, 🇬🇧 English, 🇪🇸 Spanish, 🇮🇹 Italian, 🇬🇷 Greek, and 🇫🇷 French.
- **🛡️ Enhanced Stability & BLE MTU 512 Negotiation:** Added BLE `requestMtu(512)` for modern dashboard firmwares (e.g. SV=2.0.4), MediaProjection token caching, robust BLE command queues, wakelock timeout protections, circular log file limits, and hardened TCP socket lifecycle management *(Special thanks to Dominik Ladoš)*.

---

## Protocol Structure and Communication

Kove TFT screens use a combination of Bluetooth (BLE) and Wi-Fi (TCP) to negotiate and stream the display:

### 1. Wi-Fi Network
The motorcycle broadcasts its own Wi-Fi Hotspot (typically with the IP `192.168.10.1`). When the phone connects, it is assigned a local IP (`192.168.10.2`). The app uses Android's `bindProcessToNetwork` API to force all application traffic through the motorcycle's Wi-Fi, allowing the phone to maintain internet connectivity over Mobile Data for other background applications (ensure your phone's "Use mobile data when Wi-Fi has no internet" setting is enabled).

### 2. TCP Ports
The phone acts as the Server, opening three distinct TCP server sockets that the motorcycle connects to:
- **Port 17818 (Control Port):** Used for initial binary handshakes, version exchange, and receiving continuous vehicle telemetry.
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
