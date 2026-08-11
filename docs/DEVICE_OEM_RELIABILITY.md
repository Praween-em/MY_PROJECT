# Device & OEM Reliability Guide

**Goal:** Reach ~90% auto-accept reliability across Android devices used by Rapido captains.

No Android app can guarantee 100% background reliability — OEM battery managers, Accessibility quirks, and swipe-kill behavior vary by phone. This document covers:

1. **User setup** — what captains must configure per OEM
2. **Implementation** — what SUPER RIDEX already does in code
3. **Verification** — how to confirm a device is ready
4. **Troubleshooting** — diagnose codes and fixes

---

## What is OEM?

**OEM** (Original Equipment Manufacturer) is the phone maker. Each OEM ships a custom skin on Android with different battery rules, background limits, and Accessibility behavior.

| OEM | Example brands | Skin |
|-----|----------------|------|
| Google | Pixel | Stock Android |
| Samsung | Galaxy | One UI |
| Xiaomi | Redmi, POCO | MIUI / HyperOS |
| OPPO | — | ColorOS |
| vivo | iQOO | Funtouch / OriginOS |
| realme | — | realme UI |
| OnePlus | — | OxygenOS / ColorOS |
| Motorola | Moto | Near-stock |
| Transsion | Infinix, Tecno, itel | XOS / HiOS |

**Why it matters for SUPER RIDEX:** We must detect rides in milliseconds and tap Accept before other captains. OEM skins can delay UI paint, swallow short taps, or kill our Accessibility engine in the background.

---

## Reliability target: ~90%

| Tier | What we can achieve | Notes |
|------|---------------------|-------|
| **Tier 1 — Stock-like** (Pixel, Motorola, Samsung with correct setup) | ~95%+ | Short taps win races; fewer background kills |
| **Tier 2 — Heavy OEM** (Xiaomi, OPPO, vivo, realme, OnePlus, Infinix/Tecno) | ~85–92% with full OEM setup | Requires Autostart + battery + Accessibility |
| **Tier 3 — Misconfigured or aggressive kill** | &lt;50% | Missing NLS, battery restricted, Accessibility off after swipe-kill |

The remaining ~10% is unavoidable on some devices due to OEM killing the entire app package, user force-stopping the app, or Rapido UI changes.

---

## Universal setup (all devices)

Every captain should complete these **before** going online for rides.

### Required permissions

| # | Permission | Why | How in app |
|---|------------|-----|------------|
| 1 | **Accessibility Service** | Finds and taps Accept inside Rapido Captain | Settings → Permissions Setup, or Home toggle |
| 2 | **Battery optimization OFF** | Stops Android from killing the engine when screen is off | "Request Battery Exemption" → Don't optimize |
| 3 | **Notification Listener** (strongly recommended) | Earliest ride signal — often faster than Accessibility alone | Settings → Notification access → SUPER RIDEX |
| 4 | **POST_NOTIFICATIONS** (Android 13+) | Engine status notification | Granted when toggling ON |

### Android 13+ sideloaded APK

If Accessibility shows **"Restricted setting"**:

1. Tap **Try Enable Accessibility** → toggle ON (will fail — expected)
2. Open **App Settings** → menu (⋮) → **Allow restricted settings**
3. Confirm PIN/fingerprint
4. Return to Accessibility → toggle SUPER RIDEX **ON** again

Samsung may show "Allow restricted settings" directly on the App Info page.

### Recommended usage habits

- Keep **Rapido Captain** in foreground or recent apps while online
- Turn **Auto-accept ON** only when subscription is active
- After app update/reinstall: re-enable Accessibility (off → on once)
- Use **Service Reliability** screen to verify health before a shift
- Do not **Force stop** SUPER RIDEX in App Info

---

## OEM-specific user instructions

Menu names change by OS version. Look for **Autostart**, **Auto-launch**, **Battery**, and **Background activity**.

### Quick reference table

| OEM ID | Phones | Autostart required? | Battery setting | Extra steps |
|--------|--------|---------------------|-----------------|-------------|
| `xiaomi` | Xiaomi, Redmi, POCO | **Yes — verify manually** | No restrictions | Lock app in Recents; HyperOS battery saver off |
| `oppo` | OPPO | **Yes — verify manually** | Allow background activity | Auto-launch in App management |
| `vivo` | vivo, iQOO | **Yes — verify manually** | Allow background / High background power | Funtouch/OriginOS names vary |
| `realme` | realme | **Yes — verify manually** | Allow background activity | Startup manager if shown |
| `oneplus` | OnePlus | **Yes — verify manually** | Unrestricted / Allow background | Auto-launch if shown |
| `samsung` | Samsung Galaxy | No dedicated menu | **Never sleeping apps** | Remove from Sleeping / Deep sleeping |
| `motorola` | Motorola | Usually not | Unrestricted if needed | Check battery optimization |
| `pixel` | Google Pixel | No OEM autostart | **Unrestricted** (optional, uses more battery) | Stock Android — simplest setup |
| `infinix` | Infinix, Tecno, itel | **Yes — verify manually** | Allow background | Treated as **Heavy** in engine |
| `generic` | Other brands | Verify if menu exists | Disable battery restriction | Enable Accessibility |

> **Important:** Android cannot read whether Autostart is granted. The app asks the user to confirm manually on the **Service Reliability** screen (`Mark Autostart verified`).

---

### Xiaomi / Redmi / POCO (MIUI / HyperOS)

**Source:** `src/utils/oemGuide.js` → `OEM_GUIDES.xiaomi`

1. Settings → Apps → Permissions → **Autostart** (or App launch)
2. Enable Autostart / allow background launch for **SUPER RIDEX**
3. App info → Battery → **No restrictions** / Allow background
4. HyperOS/MIUI: Battery saver / App battery saver → **No restrictions**
5. Keep Accessibility Service enabled
6. Optional: lock SUPER RIDEX in Recents (reduces swipe-kill)

**Known issues:** Aggressive package kill on swipe; stale `enabled=false` in memory; short taps swallowed → engine uses **Heavy** profile.

---

### OPPO (ColorOS)

1. Settings → Apps → App management → SUPER RIDEX
2. Enable **Auto-launch / Auto-start** if shown
3. Battery → **Allow background activity**
4. Keep Accessibility enabled
5. Menu names differ by ColorOS version

**Known issues:** ColorOS often keeps stale enabled flag — app re-asserts `setServiceEnabled(true)` on Home focus.

---

### vivo / iQOO (Funtouch / OriginOS)

1. Settings → Apps → SUPER RIDEX
2. Battery → Allow background activity
3. Enable **Auto-start / High background power consumption** if shown
4. Keep Accessibility enabled

**Known issues:** Late Accept button paint; among the most aggressive background killers.

---

### realme (realme UI)

1. Settings → Apps → App management → SUPER RIDEX
2. Battery usage → **Allow background activity**
3. Enable **Auto-launch / Startup manager** if available
4. Keep Accessibility enabled

---

### OnePlus

1. Settings → Apps → App management → SUPER RIDEX
2. Battery → Allow background / **Unrestricted**
3. Enable Auto-launch if shown
4. Keep Accessibility enabled

Note: Newer OnePlus devices run ColorOS-based builds — follow OPPO steps if menus match.

---

### Samsung (One UI)

1. Settings → Battery → **Background usage limits**
2. Add SUPER RIDEX to **Never sleeping apps**
3. Remove from Sleeping / Deep sleeping apps if listed
4. Avoid aggressive Power saving while online
5. Keep Accessibility enabled

**Engine note:** Samsung uses **Stock** timing (short ~8ms taps) — not classified as Heavy OEM.

---

### Motorola

1. Settings → Apps → SUPER RIDEX
2. App battery usage → Allow background / Unrestricted
3. Ensure battery optimization is not restricting the app
4. Keep Accessibility enabled

---

### Google Pixel / Stock Android

1. Settings → Apps → SUPER RIDEX → **App battery usage**
2. Choose **Unrestricted** for maximum reliability (uses more battery)
3. Keep Accessibility enabled

No OEM Autostart menu exists on stock Android.

---

### Infinix / Tecno / itel (Transsion)

1. Settings → Apps → SUPER RIDEX
2. Allow background activity / disable battery restriction
3. Look for Autostart / Auto-launch
4. Keep Accessibility enabled

Classified as **Heavy OEM** in the race engine.

---

## Implementation methods (developers)

These are the code-level strategies that support the ~90% target.

### 1. Separate `:engine` process

**File:** `plugins/withAutoClicker.js`

Accessibility Service, Notification Listener, and foreground service run in `android:process=":engine"` with `android:stopWithTask="false"`.

| Behavior | Effect |
|----------|--------|
| User swipes SUPER RIDEX from Recents | UI process dies; **engine may keep running** |
| Aggressive OEM (Vivo/MIUI) | May kill entire package — user must re-enable Accessibility |

After deploying process changes: user must toggle Accessibility **off → on** once.

---

### 2. Dual-channel ride detection

| Channel | Class | Speed |
|---------|-------|-------|
| **Notification Listener** | `RideAlertListener.java` | Earliest — fires on `onNotificationPosted` |
| **Accessibility events** | `AutoClickerService.java` | Backup — UI tree changes, notification events |

Flow: `RideAlertListener` → `AutoClickerService.onRideSignal()` → arm → hunt → tap → verify.

NLS filters spam vs real ride alerts and handles opaque/image-only notifications on some OEMs.

---

### 3. Stock vs Heavy device profiles

**File:** `native-android/src/AutoClickerService.java` → `detectAndApplyDeviceProfile()`

Detection uses `Build.MANUFACTURER`, `Build.BRAND`, `Build.FINGERPRINT`, `Build.DISPLAY`.

#### Heavy OEM list (auto-detected)

OPPO, realme, OnePlus, Xiaomi, Redmi, POCO, vivo, iQOO, Huawei, Honor, Tecno, Infinix, itel, Transsion — plus fingerprint matches for ColorOS, MIUI, HyperOS, Funtouch, OriginOS, realme UI.

#### Profile comparison

| Setting | Stock (Pixel, Moto, Samsung) | Heavy (MIUI, ColorOS, etc.) |
|---------|------------------------------|-----------------------------|
| Tap duration | ~8ms | ~20ms + extra strikes |
| Armed BG hunt poll | ~2ms | ~1ms (denser) |
| NLS follow-up chain | Up to ~2.4s | Up to ~6s |
| Race arm window | 10s default | ≥7s extended |
| Micro extra strikes | 3 | 3 (with longer press) |

**Rationale:** Heavy OEMs delay Accessibility UI paint and swallow ultra-short taps. Stock phones win races with shorter taps.

---

### 4. Accept race state machine

**File:** `docs/ACCEPT_EDGE_CASES.md`, `AutoClickerService.java`

```
IDLE → ARMED → STRIKING → VERIFYING → COOLDOWN → IDLE
```

| Phase | Purpose |
|-------|---------|
| ARMED | Ride signal received; hunting Accept in UI tree |
| STRIKING | Tap Accept (gesture + ACTION_CLICK + micro-bursts) |
| VERIFYING | Confirm Accept disappeared (real success, not fake PendingIntent) |
| COOLDOWN | ~400ms pause to avoid double-tap spam |

**Watchdog:** Every 1s while enabled — expire cooldown, recover stuck phases, reschedule hunt.

Key recovery methods:
- `recoverIfRaceStuck()` — unstuck STRIKING/VERIFYING
- `prepareForNewRideSignal()` — new ride while still verifying
- `deferRideSignal()` — queue signal during short cooldown

---

### 5. Multi-strategy Accept tap

On Heavy OEMs the engine uses layered click strategies:

1. **Notification Accept PendingIntent** (fastest when available)
2. **Accessibility ACTION_CLICK** on Accept node
3. **GestureDescription** tap at Accept center (not card center)
4. **Second press** ~10ms later on Heavy OEMs if first tap swallowed
5. **Micro-burst** retries within 800ms retry window

Accept labels include English, Hindi, Telugu, and other regional Rapido strings.

---

### 6. Cross-process config & health

| Component | File | Role |
|-----------|------|------|
| Shared prefs | `AutoClickerConfig.java` | `enabled`, `nuclearMode`, monitored packages |
| Health snapshot | `ServiceHealth.java` | Timestamps written by `:engine`, read by UI |
| JS bridge | `AutoClickerModule.java` | `getServiceHealth()`, `setServiceEnabled()` |
| OEM detection | `ServiceHealth.detectOemId()` | Drives reliability UI guides |

ColorOS fix: Home screen re-asserts `setServiceEnabled(true)` when subscription active — prevents stale OFF in engine memory.

---

### 7. OEM guides in UI

| File | Purpose |
|------|---------|
| `src/utils/oemGuide.js` | English OEM step lists |
| `src/i18n/reliability.js` | en / hi / te translations |
| `src/screens/ServiceReliabilityScreen.js` | Health dashboard + OEM instructions + diagnose |

Reliability "all good" requires:
- Accessibility ON + service connected
- Master auto-accept ON
- Battery optimization ignored
- Notification Listener ON
- Autostart manually verified
- Diagnose code `OK`

---

### 8. Permissions API

**File:** `src/services/permissions.js`

| Function | Native method |
|----------|---------------|
| `isAccessibilityEnabled()` | Checks our AccessibilityService is active |
| `isNotificationListenerEnabled()` | Checks NLS binding |
| `isBatteryOptimizationIgnored()` | Checks REQUEST_IGNORE_BATTERY_OPTIMIZATIONS |
| `openAccessibilitySettings()` | Android 13+ opens app-specific a11y page |
| `requestBatteryOptimizationExemption()` | System "Don't optimize" dialog |

**File:** `src/hooks/usePermissions.js` — re-checks on app foreground.

---

## Pre-shift verification checklist

Use this before going online. Target: all items checked.

```
[ ] Subscription active (Home shows ACTIVE)
[ ] Accessibility ON for SUPER RIDEX
[ ] Auto-accept toggle ON (Home)
[ ] Notification access ON for SUPER RIDEX
[ ] Battery optimization = Don't optimize / Unrestricted
[ ] OEM Autostart verified (Heavy OEM only)
[ ] Service Reliability → Diagnose = OK (or wait for first ride)
[ ] Rapido Captain notifications allowed
[ ] Test: simulate or wait for one ride — confirm accept + latency shown on Home
```

### After app update or reinstall

```
[ ] Re-enable Accessibility (toggle off → on)
[ ] Re-grant Notification access if prompted
[ ] Re-verify Autostart (Heavy OEM)
[ ] Re-request battery exemption if status shows restricted
```

---

## Diagnostic codes

**Source:** `ServiceHealth.diagnose()`, `src/utils/oemGuide.js` → `DIAGNOSE_COPY`

| Code | Meaning | Likely cause | Fix |
|------|---------|--------------|-----|
| **A** | Accessibility stopped | OEM killed service; user disabled | Re-enable Accessibility |
| **B** | No accessibility events | Service connected but frozen | Toggle a11y off/on; reboot phone |
| **C** | No ride signal yet | NLS off or no ride received | Enable Notification access; open Captain |
| **D** | Ride detected, Accept not found | UI changed; late paint; wrong window | Update app; Heavy OEM — wait for follow-ups |
| **E** | Accept found, click failed | Tap swallowed; wrong bounds | Heavy OEM profile; check Autostart/battery |
| **F** | Next ride missed after success | Stuck phase / cooldown latch | Usually self-recovers via watchdog; restart a11y |
| **OK** | Healthy | — | Continue monitoring |

Open **Settings → Service Reliability → Diagnose** for live phase, last event ages, and OEM label.

---

## OEM testing matrix (QA)

Test on at least one device per row before release.

| Device class | Test device examples | Must verify |
|--------------|---------------------|-------------|
| Stock | Pixel 6+, Moto G | 8ms tap accepts; swipe-kill survival |
| Samsung | Galaxy A/M series | Never sleeping apps; One UI a11y |
| Xiaomi | Redmi Note | Autostart + MIUI battery; stale enabled fix |
| OPPO/realme | A series | ColorOS Autostart; re-assert enabled |
| vivo | Y/V series | Late Accept paint; NLS follow-up chain |
| Infinix/Tecno | Hot/Spark | Heavy profile; background survival |

### Per-device test script

1. Complete universal + OEM setup
2. Enable auto-accept with active subscription
3. Open Rapido Captain, go online
4. Receive **3 consecutive ride offers** (or simulate notifications)
5. Record: accepted count, latency ms, any diagnose code
6. Swipe SUPER RIDEX from Recents → receive 1 more ride → confirm engine still works
7. Lock screen for 5 min → receive ride → confirm still works

**Pass criteria:** ≥2/3 rides accepted in step 4; step 6–7 pass on stock/Samsung; step 6 may fail on Vivo/MIUI (document as known limitation).

---

## Known limitations (why not 100%)

| Limitation | Impact |
|------------|--------|
| OEM kills entire package on swipe | Accessibility must be re-enabled manually |
| Autostart not readable by Android | User may skip — app cannot detect |
| Rapido UI / package changes | Accept finder may miss new layouts (code D) |
| Network/subscription offline grace | Engine runs on local enabled flag; periodic refresh shuts off when expired |
| Force stop by user | All services dead until manual restart |
| Low RAM / 4-core devices | Marked low-end; tree walk cap unchanged to preserve race speed |

---

## Future improvements (roadmap)

Ideas to push reliability toward 95%+:

| Improvement | Effort | OEM benefit |
|-------------|--------|-------------|
| Deep-link intents to OEM Autostart settings (where documented) | Medium | Xiaomi, OPPO, vivo |
| Periodic `:engine` heartbeat + user alert if a11y dies | Medium | All |
| Cached Accept coordinates per Rapido version | High | Late paint OEMs |
| Firebase/Crashlytics for diagnose code telemetry | Medium | Data-driven OEM fixes |
| In-app video guides per OEM | Low | User compliance |
| WorkManager alarm to ping engine health | Medium | Background kill detection |

---

## File reference

| Topic | Path |
|-------|------|
| Race engine | `native-android/src/AutoClickerService.java` |
| Notification detection | `native-android/src/RideAlertListener.java` |
| Device profile detection | `AutoClickerService.detectAndApplyDeviceProfile()` |
| OEM ID detection | `native-android/src/ServiceHealth.java` |
| OEM user guides | `src/utils/oemGuide.js` |
| Reliability UI | `src/screens/ServiceReliabilityScreen.js` |
| Permissions | `src/services/permissions.js` |
| Edge cases | `docs/ACCEPT_EDGE_CASES.md` |
| Manifest / process setup | `plugins/withAutoClicker.js` |
| Build plugin copies Java into APK | `plugins/withAutoClicker.js` → `JAVA_FILES` |

---

## Summary

**For captains:** Universal permissions + OEM-specific Autostart/battery steps + Service Reliability check ≈ **90% reliability**.

**For developers:** `:engine` process + NLS/a11y dual channel + Stock/Heavy profiles + state machine watchdog + OEM-aware UI guides implement the technical side. Remaining failures are mostly OEM policy (background kill) or user misconfiguration — document honestly and guide users through Service Reliability.
