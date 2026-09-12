# Ridio Engine Baseline Record

Date opened: 2026-09-11  
Runbook step: Step 0 — Protect the baseline  
Collection status: Blocked; physical device required

## 1. Build identity

| Field | Baseline value |
|---|---|
| Repository commit | `3c2ab265e580cabd0d81a5e4cb4a1af48b2a99c8` |
| Worktree | Dirty; preserve unrelated existing changes |
| App name | Ridio App |
| App version | `1.0.0` |
| Android package | `com.ridio.app` |
| Expo SDK | `57.0.0` |
| React Native | `0.86.0` |
| React | `19.2.3` |
| Node.js | `22.20.0` |
| npm | `10.9.3` |
| Native engine version | Not yet defined |
| Android version code | Not explicitly defined |

Never place API keys, OTP credentials, notification text, passenger details, or route addresses in this record.

## 2. Current engine constants

| Constant | Current value |
|---|---:|
| `RACE_COOLDOWN_MS` | 50 ms |
| `COOLDOWN_HARD_CAP_MS` | 800 ms |
| `VERIFY_WINDOW_MS` | 400 ms |
| `VERIFY_POLL_MS` | 50 ms |
| `VERIFY_MAX_RESTRIKES` | 2 |
| `ACCEPT_HUNT_POLL_MS` | 1 ms |
| `ARMED_BG_HUNT_POLL_MS` | 2 ms |
| `ARMED_BG_HUNT_POLL_HEAVY_MS` | 1 ms |
| `RACE_ARM_MS` | 10,000 ms |
| `RACE_ARM_MAX_FROM_SIGNAL_MS` | 18,000 ms |
| `CACHED_ACCEPT_STRIKE_MAX_AGE_MS` | 12,000 ms |
| `PREDICTIVE_CACHE_MAX_AGE_MS` | 24 hours |
| `ALERT_SPRAY_WINDOW_MS` | 700 ms |
| `ALERT_SPRAY_MAX_TAPS` | 80 |
| `OLA_UNLOCK_FROM_ALERT_MS` | 5,000 ms |
| `OLA_UNLOCK_GRACE_MS` | 160 ms |
| `OLA_ONE_SHOT_TAP_MS` | 90 ms |
| `OLA_MAX_SHIZUKU_TAPS` | 2 |
| `STUCK_STRIKING_MS` | 1,800 ms |
| `STUCK_VERIFYING_MS` | 2,800 ms |
| `RACE_WATCHDOG_MS` | 1,000 ms |
| `RACE_WATCHDOG_HOT_MS` | 500 ms |

## 3. Device availability

`adb devices -l` found no attached device when this record was created.

Step 0 cannot pass until at least one physical Android device is connected and real or controlled ride events are collected. An emulator is insufficient for OEM, Accessibility overlay, background-survival, and Shizuku timing conclusions.

## 4. Build prerequisite status

Razorpay was removed from the application. The environment check now validates the backend URL only. Build with:

```powershell
npm run env:check
npx expo prebuild --platform android --clean
Set-Location android
.\gradlew.bat assembleRelease
```

Expected universal APK:

```text
android/app/build/outputs/apk/release/app-release.apk
```

## 5. Per-device identity

Complete one copy of this table for every device.

| Field | Value |
|---|---|
| Baseline device ID | |
| Manufacturer / model | |
| Android version / API | |
| OEM skin/version | |
| RAM class | |
| CPU cores | |
| Screen width × height | |
| Density DPI | |
| Navigation mode | Gesture / three-button |
| Battery saver | On / off |
| Thermal state | |
| Rapido package/version code | |
| Ola package/version code | |
| Accessibility | Enabled / disabled |
| Notification listener | Enabled / disabled |
| Battery optimization | Ignored / restricted |
| Shizuku | Missing / stopped / denied / ready |
| Ridio removed from Recents | Yes / no |

Useful identity commands:

```powershell
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell wm size
adb shell wm density
adb shell dumpsys meminfo com.ridio.app
adb shell dumpsys package com.rapido.rider
adb shell dumpsys package com.olacabs.oladriver
```

## 6. Sample collection protocol

For each sample:

1. Confirm Ridio, device, and partner-app identities.
2. Confirm Accessibility and notification access.
3. Record Shizuku state.
4. Clear Logcat immediately before the controlled offer.
5. Receive one eligible offer.
6. Record only structured race events and timestamps.
7. Confirm the terminal outcome from the partner UI.
8. Mark uncertain evidence as `UNKNOWN`.
9. Redact notification/tree text before saving logs.
10. Keep network, account, battery, and thermal conditions as stable as possible.

Suggested capture:

```powershell
adb logcat -c
adb logcat -v epoch AutoClickerService:I RideAlertListener:I ShizukuInput:I *:S
```

Stop capture after the race reaches a terminal state.

## 7. Required sample groups

Collect at least 20 eligible offers before changing timing behavior.

| Group | Minimum samples | Required state |
|---|---:|---|
| First ride | 2 | First offer after service connection |
| Repeated rides | 8 | Include rides 5, 10, and 20 without reopening Ridio |
| Partner foreground | 2 | Rapido/Ola is active app |
| Overlay over another app | 2 | Valid ride overlay; bubble-only case separate |
| Screen locked | 2 | Screen locked before signal |
| Ridio removed from Recents | 2 | `:engine` expected to remain |
| NLS/a11y recovery | 2 | Rebind or service reconnect scenario |

The same offer may satisfy more than one group, but the record must state every active condition.

## 8. Sample record

| Sample | Device ID | Partner/version | Ride number | Scenario | Signal source | Signal time | First attempt time | Accept found time | Accept gone time | Actuator | Outcome | Reason | Notes |
|---:|---|---|---:|---|---|---:|---:|---:|---:|---|---|---|---|
| 1 | | | 1 | | | | | | | | | | |
| 2 | | | | | | | | | | | | | |
| 3 | | | | | | | | | | | | | |
| 4 | | | | | | | | | | | | | |
| 5 | | | 5 | | | | | | | | | | |
| 6 | | | | | | | | | | | | | |
| 7 | | | | | | | | | | | | | |
| 8 | | | | | | | | | | | | | |
| 9 | | | | | | | | | | | | | |
| 10 | | | 10 | | | | | | | | | | |
| 11 | | | | | | | | | | | | | |
| 12 | | | | | | | | | | | | | |
| 13 | | | | | | | | | | | | | |
| 14 | | | | | | | | | | | | | |
| 15 | | | | | | | | | | | | | |
| 16 | | | | | | | | | | | | | |
| 17 | | | | | | | | | | | | | |
| 18 | | | | | | | | | | | | | |
| 19 | | | | | | | | | | | | | |
| 20 | | | 20 | | | | | | | | | | |

## 9. Baseline aggregates

Do not calculate accepted latency from `PendingIntent.send()`, `performAction()`, gesture completion, or injection return alone.

| Metric | Rapido | Ola | Notes |
|---|---:|---:|---|
| Eligible samples | | | |
| Confirmed accepted | | | |
| Missed/expired | | | |
| Unknown | | | |
| Signal → first attempt p50 | | | |
| Signal → first attempt p90 | | | |
| Signal → first attempt p99 | | | |
| Signal → Accept found p50 | | | |
| Accept found → action p50 | | | |
| Attempt → Accept gone p50 | | | |
| False arms | | | |
| False taps | | | |
| Ride 20 operational | | | |

## 10. Step 0 gate

- [x] Build identity recorded.
- [x] Current timing constants recorded from source.
- [x] Sensitive-data restrictions documented.
- [ ] Backend URL environment check passes.
- [ ] Baseline release APK builds.
- [ ] Physical device connected.
- [ ] Device and partner-app versions recorded.
- [ ] At least 20 eligible samples collected.
- [ ] p50/p90/p99 calculated.
- [ ] False-arm and false-tap counts recorded.
- [ ] Ride 5/10/20 survival verified.

Step 0 remains open until the unchecked items are completed. Timing, spray, cache-age, and unlock behavior must not be tuned before this gate passes.
