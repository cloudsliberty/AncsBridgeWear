# ANCS Bridge — Wear OS ↔ iPhone notification mirroring

A standalone Wear OS app that connects directly to a paired iPhone over Bluetooth LE and uses
Apple's **ANCS** (Apple Notification Center Service) to mirror notifications onto the watch —
no phone-side app or Android phone required. This is the same mechanism third-party watches like
the original Pebble used before iOS had any official watch-app story.

## How it works

```
iPhone (ANCS GATT server, built into iOS)
   │  BLE, bonded/encrypted link
   ▼
Wear OS watch (this app, GATT central)
   ├─ AncsGattCallback   – subscribes to Notification Source + Data Source, drives Control Point
   ├─ AncsParser         – decodes the TLV attribute streams into AncsNotification
   ├─ AncsService         – foreground service owning the GATT session + request/response state
   └─ WearNotificationManager – renders/cancels the mirrored notification on the watch
```

Flow for a single notification:
1. iPhone notifies **Notification Source** with a compact `EventID/EventFlags/CategoryID/UID` packet.
2. The watch writes a `GetNotificationAttributes` command to **Control Point** asking for the title/subtitle/message for that UID.
3. The iPhone streams the (possibly multi-packet) TLV response over **Data Source**.
4. The watch reassembles it, resolves the app's display name (cached after the first lookup via `GetAppAttributes`), and posts a normal watch notification.
5. Swiping the notification away on the watch sends `PerformNotificationAction(Negative)` back to the iPhone, and an ANCS "Removed" event from the iPhone cancels the watch notification — so dismissal stays in sync in both directions.

## Setup (VS Code)

1. Install the **Kotlin** and **Android iOS Emulator/ADB** extensions, plus the standalone
   [Android command-line tools](https://developer.android.com/studio#command-line-tools-only)
   (`sdkmanager`, `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`).
   *(Note: Android Studio's Gradle/Compose tooling is genuinely smoother for this than VS Code —
   worth using if you get stuck on Gradle sync issues in VS Code.)*
2. `export ANDROID_HOME=/path/to/sdk` and add `platform-tools` to `PATH`.
3. On the watch: **Settings → Developer options → Wireless debugging**, then:
   ```bash
   adb pair 192.168.1.100:PORT      # pairing code shown on the watch, first time only
   adb connect 192.168.1.100:5555
   ```
4. From the project root:
   ```bash
   ./gradlew :app:installDebug
   ```
5. **Before** opening the app: pair the watch with the iPhone in the watch's own **system**
   Bluetooth settings (this triggers iOS's normal BLE pairing prompt). ANCS is only exposed over
   a bonded, encrypted link — the app deliberately doesn't try to reimplement pairing itself.
6. Open **ANCS Bridge** on the watch, grant the Bluetooth/notification permissions, tap your
   iPhone in the bonded-device list. iOS will show a one-time "Share System Notifications?" style
   prompt the first time the app actually requests ANCS data — accept it there.

## Real-world caveats (read before relying on this)

- **This is not an officially supported pairing path.** Wear OS's built-in phone-pairing flow is
  designed around Android companion phones; connecting a Wear OS watch to an iPhone this way
  works at the protocol level (ANCS is a published, generic BLE service) but you're outside
  Google's and Apple's tested/supported configurations. Behavior can vary across watch models,
  Wear OS versions, and iOS versions, and Apple could tighten ANCS behavior in a future iOS
  release without notice.
- **Battery/Doze**: Wear OS will aggressively suspend background BLE activity unless the app is
  exempted from battery optimization and the foreground service stays alive — expect to tune this
  per watch model.
- **Reconnection**: `connectGatt(autoConnect = true)` handles most drops, but a full iPhone
  reboot or a Bluetooth toggle on either side will usually require reopening the app once.
- **No action-label text or reply support**: this build only wires up the binary Negative
  (dismiss) action; Positive actions and Apple's separate "reply with text" mechanism aren't
  implemented.
- **Icons**: ANCS doesn't transmit per-notification app icons, only the app's display name — the
  watch notification uses a generic icon.

## Project layout

```
app/src/main/java/com/notisync/wear/ancs/
  Ancs.kt                     UUIDs + protocol constants + data models
  AncsParser.kt                Notification Source / Data Source byte-stream decoding
  AncsGattCallback.kt           BLE GATT session + Control Point request queue
  GattCompat.kt                 API 30–32 vs 33+ write() shims
  BlePairingManager.kt           Bonded-device lookup, opens system Bluetooth settings
  AncsService.kt                 Foreground service tying the above together
  WearNotificationManager.kt      Renders/cancels the mirrored watch notification
  NotificationActionReceiver.kt   Relays a watch-side dismiss back to the iPhone
  MainActivity.kt                 Wear Compose permission + device-picker UI
```
