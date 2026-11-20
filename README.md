# FreeFire-ProxyCheat

Proxy-based cheat APK for Free Fire — Shizuku + VpnService + overlay.

## Features
- **Head Lock** — rewrites outgoing aim packets toward nearest enemy head bone
- **ESP Box** — parses incoming entity state, draws boxes + snap lines over game

## Build
Push to GitHub → Actions builds `app-debug.apk` automatically.
Or locally: `./gradlew assembleDebug`

## Setup
1. Install Shizuku on device, start via ADB: `adb shell sh /sdcard/shizuku/start.sh`
2. Install FFBench APK
3. Open FFBench → tap **Connect Shizuku**
4. Tap **Inject** → tap **Start**
5. Grant Overlay + VPN permissions
6. Free Fire launches → tap ⚙ → enable Head Lock + ESP

## Notes
- FF package: `com.dts.freefireth` (global) / `com.dts.freefiremax` (some regions)
- Packet offsets in `PacketEngine.kt` may need re-verification via Wireshark
- Requires Android 9+ (minSdk 28)
