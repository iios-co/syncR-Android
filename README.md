# syncR (Android)

**syncR** is a highly optimized, background-first Android application designed for bidirectional file synchronization between an Android device and SMB network shares (SMB2/SMB3). It was purpose-built for gigabit local network throughput, massive file transfers (e.g., 3GB+ videos), and zero-friction automated background syncing.

## 🚀 Key Features

*   **Real-time Push (Phone → SMB):** Uses native Linux `inotify` (`RecursiveFileObserver`) to monitor local folders (like `DCIM/Camera`) and instantly stream new or moved files to your NAS the moment they finish writing to flash.
*   **Scheduled Pull (SMB → Phone):** Periodically polls remote SMB folders and mirrors new changes down to the Android filesystem.
*   **SSID-Aware Networking:** Sync tasks can be bound to specific Wi-Fi networks (SSIDs) so data only transfers when you are on a trusted home network.
*   **Crash-resilient WAL Queue:** Implements an ultra-lightweight Write-Ahead Log (WAL) to ensure no files are missed if the device restarts, the app crashes, or the network drops.
*   **Performance First:** 
    *   Streams files via 1MB buffered chunks to easily saturate 1Gbps Wi-Fi links.
    *   Avoids synchronous SMB directory check overheads using an EAFP (Easier to Ask Forgiveness than Permission) networking strategy.
    *   Uses unbounded `WakeLocks` dynamically to prevent the OS from sleeping mid-transfer during multi-gigabyte video uploads.
*   **Privacy-centric Logging:** All background file-level logging is managed entirely in-memory and surfaced via the UI and Notification tray. No sensitive paths or SSIDs are leaked to the system `logcat` in production builds.

## 🛠 Tech Stack
*   **Language:** Kotlin
*   **UI:** Jetpack Compose (Material 3)
*   **Concurrency:** Kotlin Coroutines & `StateFlow`
*   **SMB Protocol:** `smbj` with BouncyCastle (NTLM/crypto)

## 📦 Building from Source

To build a production release, you will need JDK 17 installed.

```bash
# 1. Clone the repository
git clone https://github.com/iios-co/syncR-Android.git
cd syncR-Android

# 2. Generate a release Keystore (if you haven't already)
keytool -genkey -v -keystore app/keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias key0 -storepass password -keypass password -dname "CN=SyncR, O=SyncR, C=US"

# 3. Build the Universal APK
./gradlew assembleRelease

# 4. (Optional) Build the Android App Bundle for Play Store
./gradlew bundleRelease
```

The resulting binaries will be located at:
*   **APK:** `app/build/outputs/apk/release/app-release.apk`
*   **AAB:** `app/build/outputs/bundle/release/app-release.aab`

## 🔒 Permissions required

Due to the nature of background syncing, the app requires:
*   `MANAGE_EXTERNAL_STORAGE` (All Files Access) — Necessary to recursively watch storage via `inotify`.
*   `ACCESS_FINE_LOCATION` — Required by modern Android to read Wi-Fi SSIDs for network-bound tasks.
*   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Allows the background daemon to reliably trigger outside of active device usage.

---
*Maintained by the iios-co team.*
