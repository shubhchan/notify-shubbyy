# Notify Shubbyy: Low-Power Backendless Instant Web Trigger & Android Receiver

An ultra-low power, 100% serverless, zero-cost notification pipeline that triggers immediate hardware audio and vibration alerts on your Android device when you hit the web FIRE button.

Styled as a **90s arcade cabinet** — neon phosphor, CRT scanlines, pixel fonts, and a giant FIRE button. The same visual language ships on the web trigger and the Android receiver.

---

## 🕹️ Design System: Pixel Signal

The full design language lives in [`DESIGN_SYSTEM.md`](DESIGN_SYSTEM.md) and [`design-system.css`](design-system.css).

- **Display / logo**: Press Start 2P
- **Labels / UI**: Silkscreen
- **Body / logs / channel codes**: VT323
- **Palette**: phosphor green `#39FF88` (online), arcade pink `#FF2E88` (siren), cyan `#3EE6FF` (info/vibrate), yellow `#FFD23F` (focus), deep-void surfaces `#07030F → #27174F`
- **Surfaces**: 3px borders, hard `4px 4px 0` pixel shadows, near-square 2px corners
- **Motion**: stepped pixel presses, blinking LEDs, CRT scanlines — all disabled under `prefers-reduced-motion`
- **Android**: the same tokens in `res/values/colors.xml`, with Press Start 2P and VT323 bundled in `res/font/`

### Web trigger upgrades

| Feature | Detail |
| --- | --- |
| **Delivery ACK** | Subscribes to `shubbyy/notify/instant/ack` and shows real round-trip latency in milliseconds. |
| **Mode persistence** | Beep / Siren / Vibrate choice is saved to `localStorage`. |
| **Mute** | Sound state persists; the synth never fires while muted. |
| **Keyboard play** | `SPACE` / `ENTER` fire, `1-3` select a mode, `M` mutes. |
| **Anti-spam** | 900ms cooldown and a 3-signal in-flight cap. |
| **Session log** | Timestamped transmission log with ACK / warning / error states and a Clear action. |
| **Resilience** | MQTT library fallback, reconnect states, no-ACK detection, and copy-topic helpers. |
| **Accessibility** | Focus rings, `aria-live` status, 44px targets, reduced-motion support. |

---

## ⚡ The Architecture: Why FCM Fails on Static Pages & Why MQTT Wins

### The Problem with Google FCM on Static Sites (GitHub Pages)
Modern Google Firebase Cloud Messaging (HTTP v1 API) enforces server-side OAuth2 service account authentication (`Bearer token` signed with a private RSA key file). 
Hosting this directly on a static site like GitHub Pages would force you to commit your sensitive private key into client-side JavaScript, exposing your entire Google Cloud/Firebase account.

### The Solution: MQTT over WebSockets
1. **GitHub Pages (Web Browser)**: Connects to a public/private MQTT broker over secure WebSockets (`wss://broker.emqx.io:8084/mqtt`). When the user clicks the FIRE button, the browser dispatches a tiny QoS 1 packet directly to the shared topic.
2. **Native Android App**: Maintains a single passive TLS socket connection (`ssl://broker.emqx.io:8883`) via a Foreground Service. With a 60-second keep-alive interval (a 2-byte ping packet), the radio remains in idle state, consuming **less than 1% battery over 24 hours**.
3. **Instant Latency**: Bypasses cloud queue throttling; round-trip trigger latency is typically **30ms – 120ms**.

> **Demo topic note:** `shubbyy/notify/instant` is a public demo topic on a public broker. For personal use, change the topic name in both `app.js` and `android/app/src/main/java/com/shubbyy/notify/MqttForegroundService.kt` so only your devices share it.

---

## 📂 Repository Structure

```
notify-shubbyy/
├── index.html                  # GitHub Pages arcade trigger interface
├── design-system.css           # Pixel Signal design tokens & components
├── DESIGN_SYSTEM.md            # Design language documentation
├── style.css                   # Cabinet layout, CRT effects, FIRE button
├── app.js                      # MQTT client, ACK tracking, chiptune synth, arcade UI
├── favicon.svg                 # Pixel-art SVG favicon
├── README.md                   # Complete architectural and setup guide
└── android/                    # Complete native Android Studio project
    ├── build.gradle.kts        # Root Gradle build script
    ├── settings.gradle.kts     # Repository management & project settings
    ├── gradle.properties       # AndroidX & JVM memory settings
    └── app/
        ├── build.gradle.kts    # App dependencies (Eclipse Paho MQTT, AndroidX)
        ├── proguard-rules.pro  # Proguard keep rules for Paho client
        └── src/main/
            ├── AndroidManifest.xml # Permissions (Foreground service, WakeLock, Boot)
            ├── java/com/shubbyy/notify/
            │   ├── MainActivity.kt          # Arcade UI, stats, battery flow, capped logs
            │   ├── MqttForegroundService.kt # Low-power listener, hardware sound & vibration
            │   ├── AlertSoundPlayer.kt      # Synthesized siren / chime / haptics
            │   ├── BootReceiver.kt          # Restarts service when device reboots
            │   └── NotificationHelper.kt    # Foreground & high-priority alert channels
            └── res/
                ├── font/                    # Bundled Press Start 2P + VT323
                ├── color/                   # Arcade switch tints
                └── ...                      # Layouts, colors, pixel drawables, adaptive icons
```

---

## 🚀 Step 1: Deploy Web Trigger to GitHub Pages

1. **Push this repository to GitHub**:
   ```bash
   git init
   git add .
   git commit -m "Initial commit: Notify Shubbyy"
   git branch -M main
   git remote add origin https://github.com/shubhchan/notify-shubbyy.git
   git push -u origin main
   ```
2. **Turn on GitHub Pages**:
   - In your repository, go to **Settings** &rarr; **Pages**.
   - Under **Build and deployment** &rarr; **Source**, select **Deploy from a branch**.
   - Select branch `main` and folder `/ (root)`.
   - Click **Save**.
3. Your web page is live at: `https://shubhchan.github.io/notify-shubbyy/`!

---

## 📱 Step 2: Android App Setup & Permissions

To guarantee 100% reliable real-time beeps without being killed by Android's aggressive battery optimizations:

### 1. Android Foreground Service
- Declared in `AndroidManifest.xml` with `android:foregroundServiceType="dataSync"`.
- Uses `NotificationHelper.kt` to run with a silent, low-priority status bar notification so Android OS never kills the process for memory allocation.

### 2. Battery Optimization Exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
- Android 6.0+ (Marshmallow) introduces **Doze Mode**. When the phone is sitting on a table with the screen off, Doze suspends network access for background apps.
- The app includes a built-in one-tap button: **"REQUEST BATTERY EXEMPTION"**.
- This launches `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, exempting the app from socket sleep.

### 3. Notification Permission (Android 13+ / API 33)
- The app checks and requests `android.permission.POST_NOTIFICATIONS` at runtime via modern `ActivityResultContracts.RequestPermission()`.

### 4. CPU WakeLock on Arrival
- When the MQTT packet arrives while the screen is off, `PowerManager.PARTIAL_WAKE_LOCK` is held for 5 seconds to ensure the hardware `ToneGenerator` and `Vibrator` run to completion before the CPU returns to deep sleep.

### 5. Auto-Start on Boot (`RECEIVE_BOOT_COMPLETED`)
- `BootReceiver.kt` listens for `ACTION_BOOT_COMPLETED`. If you previously turned on the listener, the service automatically restarts when the phone boots up.

---

## 🛠️ How to Open & Build the Android App

1. Open **Android Studio**.
2. Select **Open** and browse to the `android/` directory inside this repository.
3. Allow Gradle to sync dependencies (`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`).
4. Connect your Android phone via USB (with USB Debugging enabled) or use Wi-Fi pairing.
5. Click **Run 'app'** (`Shift + F10`).
6. In the app:
   - Tap **ENABLE NOTIFICATIONS**.
   - Tap **REQUEST BATTERY EXEMPTION** and select **Allow**.
   - Confirm the locked channel reads `shubbyy/notify/instant`.
   - Switch on the **BACKGROUND LISTENER**. The status chip turns green with **ONLINE**.
   - Optional: use **TEST BEEP**, **TEST SIREN**, or **TEST VIBRATION** to verify hardware output.

---

## 💡 Quick 0-Build Alternative: `ntfy.sh`

If you want immediate phone notifications without compiling any Android code:
1. Download the free, open-source **ntfy** app from Google Play or F-Droid on your phone.
2. In the ntfy app, subscribe to a unique topic name (e.g. `shubbyy_alert_83921`).
3. You can trigger it from anywhere with one line of JavaScript:
   ```javascript
   fetch('https://ntfy.sh/shubbyy_alert_83921', {
     method: 'POST',
     body: '⚡ Web page was triggered!',
     headers: { 'Title': 'Alert', 'Priority': 'urgent', 'Tags': 'warning,bell' }
   });
   ```
*(Both approaches are completely serverless and cost 0.00$)*.
