# UI removals after Git reset (reference)

**When:** 2026-08-07 (after hard reset to `origin/master` @ `9e0e3b6`)  
**Purpose:** Track what we stripped from the clean GitHub base so we can bisect breakage vs future work.

These changes are **local / uncommitted** unless noted otherwise.

---

## Summary of what was removed

| Feature | What users lose | Status |
|--------|-----------------|--------|
| Min ride price | Filter offers below a ₹ threshold | UI gone; native always treats min as `0` |
| Instagram link | Instagram button on Home socials | Removed from Home fetch/display |
| Subscription card extras | Plan label, days left, started date on Home | Home shows expiry date + Renew only |
| Profile plan details | Plan type + days remaining rows | Profile shows phone + expiry only |
| History tab | Ride-accept history list | Tab + screen deleted; no persist listener |
| Ride Accepted Alerts | Settings toggle for accept notifications | Toggle UI removed |

Tabs after change: **Home · Profile · Settings** (History gone).

---

## Files touched (UI removals)

| Path | Change |
|------|--------|
| `src/screens/HomeScreen.js` | Removed min-price UI/state; Instagram; subscription meta (plan/days/started) |
| `src/screens/ProfileScreen.js` | Removed Plan + Days Remaining rows |
| `src/screens/SettingsScreen.js` | Removed “Ride Accepted Alerts” section + related state |
| `src/navigation/MainTabs.js` | Removed `HistoryTab`, `HistoryScreen` import, `onRideAccepted` → `addRideAccepted` listener |
| `src/screens/HistoryScreen.js` | **Deleted** |
| `native-android/src/AutoClickerConfig.java` | `getMinPrice()` always returns `0` (ignores disk value) |

---

## Accept click fix (stuck race unlock + FG hunt)

**When:** 2026-08-07  
**Problem A:** After git reset, `recoverIfRaceStuck` was missing. If VERIFY/STRIKING hung once, `canStartRapidoBurst()` stayed false → no more taps.

**Problem B:** Hunt poll was **race-only** (`shouldRunAcceptHuntPoll` required NLS arm). On Captain FG without a strong notification arm, the engine **never polled Accept** → looked like “not clicking at all.”

**Fix in `AutoClickerService.java`:**
- Track `racePhaseEnteredAtMs`
- `recoverIfRaceStuck` / `prepareForNewRideSignal`
- **Captain FG hunt poll restored** (`isRaceActive() || rapidoForeground`)
- Status notification: `Auto-accept ON` / `OFF` (so OFF is obvious)
- Throttled `SKIP_DISABLED` log when a11y events arrive but master toggle is OFF

**Still required on device:** Auto-accept ON (check notification), Accessibility ON, Notification access ON, Nuclear mode, fresh release APK.

---

## Speed follow-up (benefit from removals)

**When:** 2026-08-07  
**Goal:** Free CPU / bridge work that History + min-fare no longer need, without touching Vivo/Realme gesture lengths.

| Change | Where | Why safer / faster |
|--------|--------|-------------------|
| Disable History soft-confirm (`HISTORY_CONFIRM_ENABLED=false`) | `AutoClickerService.java` | Stops up to **2.5s of Accept tree walks every 120ms** after VERIFY miss |
| Remove post-strike fare enrich | `AutoClickerService.java` | No `collectPriceNearAccept` after strike-0 |
| Skip NLS fare parse before arm/hunt | `AutoClickerService.java` | Faster NLS → hunt path |
| Thin accept emit (`fare=0`) | `flushConfirmedAcceptHistory` | Home session counter still works; no fare I/O |
| `RACE_COOLDOWN_MS` **1000 → 700** | `AutoClickerService.java` | Quicker re-arm for along-route offers |
| Stop reading `min_price`/`delay_ms` on hot disk sync | `AutoClickerConfig.java` | Less SharedPreferences work on `isEnabled()` |
| Drop `setNativeMinPrice(0)` / save `minPrice` IPC | `HomeScreen.js`, `autoclicker.js` | Less JS↔native chatter |

### Intentionally NOT changed (OEM risk)
- `RAPIDO_GESTURE_MS_HEAVY` / delayed 2nd press
- `VERIFY_WINDOW_MS` / restrike count
- Hunt poll intervals (`ACCEPT_HUNT_POLL_MS` etc.)
- Heavy OEM NLS follow-up delays

### If accepts get worse after this speed pass
1. Revert `RACE_COOLDOWN_MS` to `1000` first.  
2. Re-enable `HISTORY_CONFIRM_ENABLED` only if you restore History and need soft confirm.  
3. Do **not** cut heavyOem gesture first — that usually increases misses on Vivo/Realme.

---

## What was *not* changed by the UI pass alone

- Accept race engine gesture / VERIFY / hunt timings (until the speed follow-up above)
- OTP / Razorpay / subscription API flow
- Accessibility / notification permissions (NLS still required to arm)
- Backend / admin panel
- Earlier experimental work (death alerts, splash hacks, a11y yield-on-kill) — discarded by hard reset

---

## Risk notes (if something breaks)

### 1. Min price always `0`
- **Symptom:** Offers that used to be skipped by min fare are now accepted.
- **Cause:** `AutoClickerConfig.getMinPrice()` hard-returns `0`.
- **If needed again:** Restore disk read in `getMinPrice()` + Home min-price UI.

### 2. History tab / `HistoryScreen.js` deleted
- **Symptom:** Navigate to `HistoryTab` crashes.
- **Restore:** `git checkout HEAD -- src/screens/HistoryScreen.js` + re-add tab / listener in `MainTabs.js`.
- **Note:** `pendingHistoryValid` still exists in native as a **VERIFY strike gate**, not for the History screen.

### 3. Ride Accepted Alerts toggle removed
- **Symptom:** Users can’t toggle accept toasts from Settings.
- Check settings keys if notification behavior looks wrong.

### 4. Speed follow-up regressions
- **Double-tap / latch too early:** raise `RACE_COOLDOWN_MS` back toward 1000.
- **Session counter stops:** check thin `emitRideAccepted` still reaches Home `onRideAccepted`.
- **Miss after miss feels “stuck”:** history soft-watch used to eventually disarm; now miss stays ARMED (desired). Confirm hunt still runs.

---

## How to revert one piece (from repo root)

```powershell
git checkout HEAD -- path\to\file

# Examples
git checkout HEAD -- src/screens/HistoryScreen.js
git checkout HEAD -- src/navigation/MainTabs.js
git checkout HEAD -- native-android/src/AutoClickerConfig.java
git checkout HEAD -- native-android/src/AutoClickerService.java
git checkout HEAD -- src/screens/HomeScreen.js
git checkout HEAD -- src/screens/ProfileScreen.js
git checkout HEAD -- src/screens/SettingsScreen.js
git checkout HEAD -- src/services/autoclicker.js
```

Or discard **all** local removals + speed work:

```powershell
git checkout HEAD -- native-android/src/AutoClickerConfig.java native-android/src/AutoClickerService.java src/navigation/MainTabs.js src/screens/HistoryScreen.js src/screens/HomeScreen.js src/screens/ProfileScreen.js src/screens/SettingsScreen.js src/services/autoclicker.js
```

---

## Checklist before blaming these removals / speed work

1. Failure in **UI** vs **Accept race**? Race misses → check cooldown / OEM first, not History tab.  
2. Did you **prebuild + assembleRelease** after Java changes?  
3. Is `android/` out of sync with `native-android/src/`?  
4. Compare: `git diff HEAD -- <file>`

---

## Related context

- Clean base commit: `9e0e3b6` — *Add DB-backed subscription plan pricing editable from admin.*
- Hard reset discarded prior local experiments; do **not** reintroduce death-alert / yield-on-kill / splash thrash without an explicit decision.
