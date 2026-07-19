# Native Android Module — Auto Clicker Accessibility Service

Phase 1 is implemented. Native sources live in two places:

- **`native-android/src/`** — source of truth (copied into `android/` by the Expo config plugin on prebuild)
- **`android/app/src/main/java/com/rapidotap/app/`** — compiled Android project

## Files

| File | Purpose |
|---|---|
| `AutoClickerService.java` | AccessibilityService — watches UI, taps Accept |
| `AutoClickerModule.java` | React Native bridge (permissions + config + events) |
| `AutoClickerPackage.java` | Registers the native module |
| `AutoClickerConfig.java` | Shared thread-safe config |
| `BootReceiver.java` | BOOT_COMPLETED hook |
| `res/xml/accessibility_service_config.xml` | Service capability declaration |

## JavaScript bridge

`src/services/autoclicker.js` exposes:

- `setServiceEnabled`, `setMinPrice`, `setDelayMs`, `setMonitoredPackages`
- `getServiceStatus`, `saveSettings`
- `onRideAccepted(callback)` — native event when a ride is accepted

`src/services/permissions.js` already calls `AutoClickerModule` for permission checks.

## Build & test on device

```bash
# Generate / refresh android/ (plugin copies native sources + patches manifest)
npx expo prebuild --platform android

# Build and install on a connected phone
npx expo run:android
```

### First-time setup on the phone

1. Open the app → complete login (or go to Home)
2. **Settings → Accessibility → Installed services → RapidoTap → Enable**
3. Grant overlay + battery exemption via Permissions Setup screen
4. On Home: set min price, select apps, tap **SAVE SETTINGS**
5. Toggle **AUTO-ACCEPT** on
6. Open Rapido/Ola driver app and wait for a ride request

> Accessibility services must be tested on a **real Android device**. Emulators are unreliable for this.

## Re-running prebuild

The Expo plugin `./plugins/withAutoClicker.js` (registered in `app.json`) re-applies:

- AndroidManifest service + boot receiver entries
- `accessibility_service_description` string
- Java sources from `native-android/src/`
- `AutoClickerPackage()` registration in `MainApplication.kt`

## Tuning accept-button detection

Edit `ACCEPT_KEYWORDS` and `PRICE_PATTERN` in `AutoClickerService.java` if a driver app's UI uses different labels. Package names for monitored apps are configured from HomeScreen (`com.rapido.passenger`, `com.olacabs.driver`, `com.ubercab.driver`).
