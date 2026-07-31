# Native Android (SUPER RIDEX)

Source of truth for the accessibility / notification race engine:

- **`native-android/src/`** — Java sources (`package com.rapido.tap`)
- Synced into `android/` on every Expo prebuild by `plugins/withAutoClicker.js`

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

1. Install the new APK (`com.rapido.tap` — uninstall any old `com.playnix.app` build first)
2. **Settings → Accessibility → Installed services → SUPER RIDEX → Enable**
3. **Settings → Notification access → SUPER RIDEX Alerts → ON** (recommended)
4. Open SUPER RIDEX → turn Auto-accept ON
