# KoveMirror 🏍️📺

KoveMirror, Kove ve benzeri motosikletlerin TFT ekranlarına telefon ekranını yansıtmak (screen mirroring) ve kablosuz bağlantı sürecini resmi ThinkerRide uygulamasına ihtiyaç duymadan başlatmak için geliştirilmiş, açık kaynaklı bir Android uygulamasıdır.

---

## 💡 Nasıl Çalışır? (Protokol & Teknik Detaylar)

KoveMirror, resmi ThinkerRide protokolünün el sıkışma (handshake) adımlarını taklit eder:

### 1. Bluetooth BLE GATT Tetiklemesi
* Uygulama, motosikletin BLE GATT servisine bağlanır:
  * **Service UUID:** `0000e0ff-3c17-d293-8e48-14fe2e4da212`
  * **Write Characteristic:** `0000ffe1-0000-1000-8000-00805f9b34fb`
  * **Notify Characteristic:** `0000ffe2-0000-1000-8000-00805f9b34fb`
* Bağlantı sağlandıktan sonra, BLE kuyruk mekanizması ile sırasıyla şu paketler gönderilir:
  * **Pairing Check:** `{"msg_id":27,"func":"PAIR","act":"get_pairinfo"}`
  * **Version Code:** `{"msg_id":13}`
  * **Clock Sync:** Saat eşitleme paketi (`msg_id: 11`)
  * **Mirror Status:** `{"msg_id":25,"msg_type":23,"msg_source":2,"status":1}`
  * **Record Status:** `{"msg_id":25,"msg_type":21,"msg_source":2,"status":1}`
* Bu paketlerin gönderilmesiyle birlikte TFT ekranın WiFi Hotspot özelliği aktifleşir.

### 2. WiFi Kilitlenmesi (Android İnternetsiz Ağ Sorununu Aşma)
* Android cihazlarda, bağlanılan WiFi ağında internet yoksa (motosikletin AP'si gibi), telefon tüm ağ trafiğini hücresel veriye (mobil veri) yönlendirir.
* KoveMirror, bağlantı sağlandığı an tüm süreç ağını motosikletin WiFi arayüzüne kilitler (`bindProcessToNetwork`).
* TCP Server soketleri (`17818`, `15456`, `15457`), bu kilitlenmeden sonra telefonun WiFi IP adresine (örneğin `192.168.10.2`) bind edilerek açılır.

### 3. WiFi Portları ve El Sıkışma (Port 17818)
TFT ekran telefona bağlandığında şu portlar kullanılır:
* **Port 17818 (Control):** Kontrol ve el sıkışma paketleri.
  * **Paket Çerçevesi (Framing):** `[0xEE 0xFD] [4-byte Length Big-Endian] [JSON] [0xFF]`
  * **Süreç:** Bağlantı sağlandığında telefon `TUC GET` gönderir, TFT kendi kimlik kodunu (TUC) gönderir, ardından telefon durum ve e-posta doğrulama paketlerini gönderir. Bu adımlardan sonra TFT ekranındaki **WiFi logosu yanar**.
* **Port 15456 (Video):** H.264 video kodlayıcı (MediaProjection) akışının aktarıldığı porttur.
* **Port 15457 (Heartbeat):** TFT'ye her 450ms'de bir gönderilen `0x02 0x01 0x00 0x00 0x00 0x00` keep-alive kalp atışı.

---

## 🛠️ Nasıl Derlenir ve Kurulur?

### Gereksinimler
* Android SDK (API 26+)
* Gradle 8.2.2+
* Android Studio (Koala veya daha yeni sürüm)

### Terminal üzerinden Derleme & Yükleme
Telefonunuzu USB Hata Ayıklama (USB Debugging) açık şekilde bilgisayara bağlayın ve şu komutları çalıştırın:

```bash
# Projeyi derle
./gradlew assembleDebug

# Derle ve bağlı telefona yükle
./gradlew installDebug
```

---

## 📄 Lisans
Bu proje [MIT Lisansı](LICENSE) altında lisanslanmıştır.
