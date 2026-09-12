# Native Android (Ridio)

Source of truth for the Accept engine:

- **`native-android/src/`** — Java sources (`package com.ridio.app`)
- Synced into `android/` on every Expo prebuild by `plugins/withAutoClicker.js`

## How clicks work

1. **Rapido Captain** — Accessibility `ACTION_CLICK` on Accept, 0ms after the ride alert. No Shizuku.
2. **Ola Driver** — Accessibility finds Accept; **Shizuku** injects the tap after the 5s unlock.
3. **Notification access** arms the race as soon as a ride heads-up appears.

## Fresh Android build

```bash
# Remove old generated project (old package / fat APKs)
rm -rf android

npx expo prebuild --platform android --clean
cd android
./gradlew assembleRelease
```

Install the phone ABI APK from:

`android/app/build/outputs/apk/release/app-arm64-v8a-release.apk`  
(or `app-armeabi-v7a-release.apk` on older 32-bit phones)

## Enable auto-accept

1. Install the new APK (`com.ridio.app`)
2. **Settings → Accessibility → Installed services → Ridio → Enable** (required for Rapido)
3. **Settings → Notification access → Ridio Alerts → ON** (recommended)
4. Open Ridio → turn Auto-accept ON — Rapido taps immediately, no Shizuku
5. **Ola only:** Install **Shizuku** from the Play Store → Start (Wireless debugging) → Permissions Setup → grant Shizuku
6. Open **Rapido Captain** or **Ola Driver** and go online
