# TFT Screen Mirroring Protocol Specification

This document details the reverse-engineered communication protocol between Android client applications 

---

## 🧭 Protocol Overview
The connection is established in two sequential phases:
1. **Bluetooth Low Energy (BLE) GATT Connection:** Initiates connection, synchronizes system clocks, sends credentials, and triggers the TFT to enable its WiFi Access Point.
2. **WiFi TCP Socket Connections:** Bypasses Android cellular routing restrictions, binds socket listeners to the local WiFi interface, and manages the command/video streaming servers.

---

## 🔵 Phase 1: Bluetooth GATT Handshake
The phone acts as a GATT client and connects to the motorcycle's BLE MAC address.

### BLE GATT Service & Characteristics
* **Service UUID:** `0000e0ff-3c17-d293-8e48-14fe2e4da212` *(Note: some models use `0000e0ff-3c17-d293-8e48-14fe2e4da213` or `0000e0ff-3e17-d293-8e48-14fe2e4da212` depending on connection mode and flexible screen variants).*
* **Write Characteristic:** `0000ffe1-0000-1000-8000-00805f9b34fb` (Write type: `WRITE_TYPE_NO_RESPONSE`)
* **Notify Characteristic:** `0000ffe2-0000-1000-8000-00805f9b34fb`
* **Client Characteristic Configuration (Descriptor):** `00002902-0000-1000-8000-00805f9b34fb` (Must write `ENABLE_NOTIFICATION_VALUE` to start receiving TFT packets).

### BLE JSON Commands Sequence
After subscribing to notifications, the phone sends the following JSON commands sequentially (using a delay of 100-150ms between writes to avoid BLE buffer saturation):

1. **Pairing Request:**
   * **Phone -> TFT:** `{"msg_id":27,"func":"PAIR","act":"get_pairinfo"}`
   * **TFT -> Phone:** `{"msg_id":27,"func":"PAIR","act":"send_pairresult","result":1}`

2. **Query Version Code:**
   * **Phone -> TFT:** `{"msg_id":13}`
   * **TFT -> Phone:** Reports firmware/MCU versions:
     `{"msg_id":10,"item":6,"version":"UC=..._SV=..._TUC=..._CV=...","sysversion":"...","mcuversion":"...","btversion":"..."}`

3. **Query TFT Unique Code (TUC):**
   * **Phone -> TFT:** `{"msg_id":27,"func":"TUC","act":"GET"}`
   * **TFT -> Phone:** `{"msg_id":27,"func":"TUC","act":"SEND","tuc":"[TUC_HEX_STRING]","tucs":1}` (TUC acts as the device's unique serial identifier).

4. **Clock Sync:**
   * **Phone -> TFT:** `{"msg_id":11,"time":"yyyy-MM-dd HH:mm:ss","tag":-1}`
   * **Phone -> TFT:** `{"msg_id":11,"time":"yyyy-MM-dd HH:mm:ss","tag":0}`

5. **Activation Flags:**
   * **Phone -> TFT:** `{"msg_id":25,"msg_type":23,"msg_source":2,"status":1}` (Enable mirroring status)
   * **Phone -> TFT:** `{"msg_id":25,"msg_type":21,"msg_source":2,"status":1}` (Enable DVR record status)

6. **Query Capabilities & Telemetry:**
   * **Phone -> TFT:** `{"msg_id":27,"func":"CAR_INFO","act":"get_car_info"}`
   * **TFT -> Phone:** Reports fuel level, tire pressure, and endurance:
     `{"msg_id":10,"item":3,"tire_pressure":0,"remaining_oil":0,"endurance":0}`
   * **TFT -> Phone:** Reports features capabilities item 11 (e.g. `"wifi": 2` flags WiFi availability).

*To keep the BLE link active, a periodic heartbeat packet is sent every 5 seconds:*
`{"msg_id":25,"msg_type":24,"msg_source":2,"status":1}`

---

## 📶 Phase 2: WiFi Connection & Routing
Once Phase 1 BLE commands are successfully sent, the TFT dashboard launches its own WiFi Access Point (AP).

### Network Configuration
* **SSID:** Same as BLE name (e.g., `CQKY_XXXXXXXXX`).
* **Security:** Open or using a common pre-shared key.
* **TFT IP:** `192.168.10.1` (Gateway).
* **Phone IP:** `192.168.10.2` (DHCP assigned).

### Cellular Routing Bypass (Important Android Behavior)
Because the TFT WiFi Hotspot does not provide internet access, Android automatically attempts to route TCP/UDP packets through cellular data (if mobile data is enabled), breaking socket communication. 
To prevent this, the client application must:
1. Scan for the specific WiFi network via `ConnectivityManager`.
2. Bind the entire process context to that network using `ConnectivityManager.bindProcessToNetwork(network)`.
3. Start the local TCP ServerSockets bound specifically to the assigned WiFi interface IP address (`192.168.10.2`), not `0.0.0.0` (wildcard).

---

## 💬 Phase 3: WiFi Control Port (17818) Handshake
The phone starts a TCP ServerSocket on Port **`17818`**. The TFT screen connects back to the phone as a client.

### Custom Packet Framing Format
All JSON and binary packets sent on Port 17818 must be framed using the following custom layout:
```
+-------------------+---------------------------+------------------------+------------------+
| Magic (2 bytes)   | Payload Length (4 bytes)  | Payload Data           | Footer (1 byte)  |
| 0xEE, 0xFD        | Big-Endian Integer        | JSON or Binary bytes   | 0xFF             |
+-------------------+---------------------------+------------------------+------------------+
```

### Handshake Sequence on Port 17818
1. **Establish Link (TUC GET):**
   Right after connection, the phone sends a framed TUC GET query:
   `0xEE 0xFD 0x00 0x00 0x00 0x26 {"msg_id":27,"func":"TUC","act":"GET"} 0xFF`

2. **TFT Response:**
   The TFT responds with a JSON `TUC SEND` message and a raw version string:
   `UC=..._SV=..._TUC=..._CV=...`

3. **Client Parameters Synchronization:**
   Upon receiving the TFT version/TUC message, the phone replies with the following packets to finalize the handshake and light up the TFT WiFi mirroring icon:
   * **Ping Command (Binary, 6 bytes):**
     `01 01 00 00 00 00` (Type `01`, Cmd `01`)
   * **Mirror Activation Command (Binary, 10 bytes):**
     `01 17 00 00 00 04 00 00 00 02` (Type `01`, Cmd `23` [0x17], payload `0x02`)
   * **User E-mail parameter (Binary, 262 bytes):**
     `01 12 00 00 01 00 [256-byte email string padded with 0x00]` (Type `01`, Cmd `18` [0x12], length `256`)
   * **Status Command 14 (Binary, 6 bytes):**
     `01 0E 00 00 00 00` (Type `01`, Cmd `14` [0x0E])
   * **Status Command 17 (Binary, 6 bytes):**
     `01 11 00 00 00 00` (Type `01`, Cmd `17` [0x11])
   * **Navigation Queries (Framed JSON):**
     `{"msg_id":27,"func":"INSIDENAVI","query":2}`
     `{"msg_id":27,"func":"INSIDENAVI","query":1}`

---

## 📺 Phase 4: Video Projection (15456) & Dedicated Heartbeat (15457)
Once the Port 17818 control handshake is completed, the TFT connects to the remaining TCP ServerSockets opened by the phone.

### Port 15456 (Video Stream Server)
The phone starts a TCP ServerSocket on Port **`15456`**. 
1. **VideoSize Header:** Immediately upon client connection, the phone writes a **69-byte** header detailing client name and output resolution:
   * Bytes `0..64`: Client name padded with `0x00` (usually `"android"`).
   * Bytes `65..66`: Screen width (Big-Endian UInt16).
   * Bytes `67..68`: Screen height (Big-Endian UInt16).
2. **Video Payload:** A raw H.264 (AVC) NAL unit stream captured from Android's `MediaProjection` API and sent directly over the TCP socket.
3. **Heartbeat:** To prevent timeout, a 6-byte heartbeat packet must be sent every 2 seconds on Port 15456:
   `0x02 0x01 0x00 0x00 0x00 0x00`

### Port 15457 (Dedicated Heartbeat Server)
The phone starts a TCP ServerSocket on Port **`15457`**.
* The phone writes a 6-byte heartbeat packet every **450 milliseconds** to keep the screen casting session alive:
  `0x02 0x01 0x00 0x00 0x00 0x00`

---

## 📡 Telemetry, Media, Navigation & Notifications
In addition to screen mirroring, the phone and the TFT exchange high-level telemetry, media metadata, navigation guidance, and mobile notifications via Bluetooth notifications or WiFi Port 17818.

### 1. 📞 Incoming Call Notification
Sent from the phone to the TFT screen to display caller ID on the dashboard when a phone call is received:
* **Format:**
  ```json
  {"msg_id": 3, "name": "[Caller Name]", "number": "[Phone Number]"}
  ```

### 2. 🎵 Music Metadata & Status
Sent from the phone to the TFT to sync track information and playback state on the dashboard music player:
* **Track Metadata:**
  ```json
  {"msg_id": 27, "func": "MUSIC", "act": "ret_msg", "title": "[Song Title]", "author": "[Artist Name]", "album": "[Album Name]", "lyrics": "[Lyrics]"}
  ```
* **Playback Status:**
  ```json
  {"msg_id": 27, "func": "MUSIC", "act": "ret_status", "status": [0|1]}
  ```
  *(0 = Stopped/Paused, 1 = Playing)*

### 3. 🌦️ Weather & Temperature
Sent from the phone to the TFT to show weather forecasts and alerts on the screen:
* **Weather Update:**
  ```json
  {"msg_id": 8, "weather": [int_code], "temperature": "[Temp String]"}
  ```
* **Weather Warning:**
  ```json
  {"msg_id": 25, "msg_type": 12, "msg_source": 2, "weather_waring": "[Alert Text]"}
  ```

### 4. 🚗 Tire Pressure Monitoring System (TPMS)
Sent from the phone to the TFT to sync TPMS values (parsed from BLE sensors or external sensors):
* **Tire Sensor Status:**
  ```json
  {
    "msg_id": 27, 
    "func": "TIRE", 
    "act": "ret_data", 
    "index": [1|2], 
    "unit": [0|1], 
    "pre": [float_val], 
    "temp": [int_val], 
    "leak_status": [0|1], 
    "bat_status": [0|1], 
    "temp_status": [0|1], 
    "pre_status": [0|1]
  }
  ```
  *(index: 1 = Front, 2 = Rear; unit: 0 = PSI, 1 = Bar)*
* **Tire Match Status:**
  ```json
  {"msg_id": 27, "func": "TIRE", "act": "tire_match_sta", "front_match_status": [0|1], "rear_match_status": [0|1]}
  ```

### 5. 🗺️ Navigation HUD Guidance
Sent from the phone to the TFT to display Turn-by-Turn (TBT) direction graphics (icons) and distances on the dashboard while navigation is active:
* **Legacy TBT Layout (msg_id: 1):**
  ```json
  {"msg_id": 1, "icon": [int_icon_id], "next_road": "[Road Name]", "cur_retain_distance": [int_meters], "path_retain_distance": [int_meters], "remain_time": [int_seconds]}
  ```
* **Modern TBT Layout (func: NAVI):**
  ```json
  {
    "msg_id": 27, 
    "func": "NAVI", 
    "act": 3, 
    "icon": [int_icon_id], 
    "next_road": "[Road Name]", 
    "cur_retain_distance": "[dist_str]", 
    "cur_unittype": [0|1], 
    "path_retain_distance": "[dist_str]", 
    "path_cur_unittype": [0|1], 
    "cur_retain_time": [int_seconds], 
    "remain_time": [unix_timestamp], 
    "retain_rate": [percentage]
  }
  ```

---

## 🎮 Handlebar Button Events & Key Tracking
Motorcycle handlebar switch cubes typically feature navigation buttons (Up, Down, OK, Back) and media/voice buttons. The protocol handles them differently:

### 1. General Menu Navigation (Up, Down, OK, Back)
* **Local Processing:** Menu navigation presses are handled locally by the TFT dashboard's microcontroller. 
* **Transmission:** These key presses **are NOT transmitted** to the phone over BLE or WiFi. 
* **HID Emulation:** The TFT does not expose a standard Bluetooth HID profile (keyboard/gamepad), meaning standard Android listeners (`onKeyDown`) cannot capture general navigation clicks.

### 2. Media Control Buttons (Play, Pause, Next, Previous)
When the user triggers media playback buttons (or long-presses navigation buttons mapped to media actions), the TFT sends a JSON event over either the BLE notification characteristic or WiFi Port 17818.

* **Format:**
  ```json
  {"msg_id": 27, "func": "MUSIC", "act": "control", "status": [0|1|2|3]}
  ```
* **Status Mappings:**
  * **`0` (Pause):** Dispatches standard Android key event `KEYCODE_MEDIA_PLAY_PAUSE` (85) or `KEYCODE_MEDIA_PAUSE` (127).
  * **`1` (Play):** Dispatches standard Android key event `KEYCODE_MEDIA_PLAY` (126).
  * **`2` (Previous):** Dispatches standard Android key event `KEYCODE_MEDIA_PREVIOUS` (88).
  * **`3` (Next):** Dispatches standard Android key event `KEYCODE_MEDIA_NEXT` (87).

### 3. Voice Assistant / Microphone Wake Key
When the user presses the voice command button on the handlebar, the TFT sends a wake-up signal to activate the phone's speech recognition assistant.

* **Format:**
  ```json
  {"msg_id": 27, "func": "AUDIO", "act": "WAKE"}
  ```
  *(Alternative LED panel format: `{"msg_id": 27, "func": "LED", "act": "start_voice"}`)*
* **Response:** The client application intercepts this and triggers the microphone recording / voice assistant overlay.
