# Reference Tapper Static Analysis

Date: 2026-09-11  
Scope: Four APKs in `reference applications/`, compared with Ridio's current native Android engine.

## Executive conclusion

Ridio is not missing a secret universal click API. All four reference products use the same basic Android primitives: accessibility events, accessibility-node searches, `ACTION_CLICK`, accessibility gestures, notification actions, and—where available—Shizuku shell input.

From static code structure, Ridio has the strongest **earliest-signal and predictive Rapido path** because it is the only analyzed app with a dedicated `NotificationListenerService`, a persisted Accept-coordinate cache, an immediate cached-coordinate spray, direct `IInputManager` injection, notification-action execution, and live-node confirmation in one pipeline.

FastClicker is the strongest reference implementation. Its most useful ideas are:

1. a dedicated high-priority scanner thread;
2. aggressive event coalescing rather than doing every scan on the service main queue;
3. detailed per-app view-ID lists;
4. precise Ola countdown handling (`ACCEPT IN 1`, a roughly 960 ms timer, then a 16 ms readiness loop);
5. extensive boot, alarm, wake-lock, heartbeat, and service-recovery behavior.

The main area where Ridio may be behind FastClicker is **Ola unlock timing and long-session recovery**, not raw Rapido first-tap speed. Ridio's primary technical risk is its very large `AutoClickerService` and the amount of polling, verification, and state management sharing one service/main queue.

This is a static-analysis conclusion, not a measured market-speed result. Real ranking requires side-by-side tests on identical phones, network conditions, accounts, and ride alerts.

## Method and confidence

The APKs were decoded with JADX 1.5.6. Decompiled code is not identical to source code and control flow can be reconstructed incorrectly, especially in obfuscated Kotlin applications. Conclusions are classified as:

- **Confirmed**: directly visible in manifest, resources, or readable method bodies.
- **Probable**: supported by several call sites but affected by obfuscation/decompiler gaps.
- **Not established**: permission or library exists, but no hot-path use was found.

No reference APK was installed or executed. No login, subscription, API, or partner-app protection was bypassed. The analysis is limited to local static artifacts.

JADX reported partial method errors but produced readable target services:

| APK | JADX method errors | Effect on confidence |
|---|---:|---|
| FastClicker 2.19.55 | 10 | High confidence; a few large methods are incomplete |
| RideClicker 1.1.4 | 124 | Medium confidence; `onAccessibilityEvent` itself was not reconstructed |
| Ride Accepter Pro Max 1.0 | 420 | High confidence for its readable `AutoAcceptService`; many dependency errors |
| RiderAccepter 1.6 | 113 | High confidence for its readable automation service |

## Artifact identity

| File | Package / version | SHA-256 |
|---|---|---|
| `FastClicker-v2.19.55.apk` | `com.fastclicker` / 2.19.55 | `1bdc0f0264f7c58e69d00ac150948acb123b272d696bd984bebb59b617271334` |
| `RideClicker.apk` | `com.rideclicker` / 1.1.4 | `7d718e602acf99b95636da0bc7169f2a96e98d364e473febbb3c8c62092a8cb6` |
| `rideaccepter-pro-max.apk` | `com.rideaccepterpro.max` / 1.0 | `474219341fd2a9e65ac7bb8f86e5153c98050604d36a5c436266e38ce96b417c` |
| `rideraccepter.apk` | `com.algomatrix.rideracceptor` / 1.6 | `716a88461438d7f4f1459072773e41c5326732e8dbd05a299af0590372159ed1` |

## Architecture comparison

The ratings below describe the statically visible design, not observed booking success.

| Capability | Ridio (current) | FastClicker | RideClicker | Pro Max | RiderAccepter |
|---|---|---|---|---|---|
| Earliest ride signal | **Dedicated NLS + accessibility notification event** | Accessibility notification event | Accessibility notification event | Accessibility notification event | Accessibility notification event |
| Notification Accept action | **Yes** | Not established; filter-reject action is confirmed | Not established because main dispatch methods are not decompiled | No; opens `contentIntent` only | Yes |
| Persisted predictive Accept coordinate | **Yes, per package** | No equivalent persisted pre-alert cache confirmed | No equivalent confirmed | Coordinates retained in memory for multi-app mode | No equivalent confirmed |
| Immediate pre-tree tap | **Yes, cached 1 ms spray** | Event-driven immediate 1 ms methods, but not a confirmed NLS pre-tree cache spray | Parallel node/gesture once target is found | No; waits 80 ms and scans all windows | No; notification action first or 35 ms screen retry |
| Node discovery | Text, view ID, bounds, multi-window, bounded tree | Text, extensive view IDs, geometry, multi-window | Text/geometry/card/sibling parsing | Recursive full-tree exact text/content description | Text, platform profiles, geometry, wide-button fallbacks |
| `ACTION_CLICK` | **Primary Rapido path** | Yes, parent fallbacks | Yes, including parallel executor paths | Yes, climbs all parents | Yes |
| Accessibility gesture | **1 ms primary fallback/parallel channel** | 1 ms and app-specific 60 ms paths | 1 ms, 40/150 ms and swipe paths | Fixed 50 ms tap | 7 ms taps plus platform-specific swipes |
| Shizuku use for tapping | **Direct `IInputManager` + shell fallback** | Shell `input swipe` press, minimum 20 ms | Shizuku confirmed for device-settings work; tap injection not established | Shell `input tap`, mainly multi-app mode | None found |
| Ola-specific unlock logic | Countdown-aware plus 5 s fallback and 160 ms grace | **Strong: `ACCEPT IN 1`, 960 ms timer, 16 ms readiness loop, serialized verification** | App-specific delayed path; exact behavior partly obscured | No unlock-specific path found | No clear lock-aware wait; blind-area retries exist |
| Verification | Accept-gone/live-card state machine | Detailed app-specific verification and controlled retries | Detailed success bookkeeping is present; full path partly obscured | No meaningful accept-gone verification | 1 s delayed accepted/missed/still-visible verification |
| Long-session survival | **`:engine` sticky FGS, NLS rebind/replay, watchdog, tree refresh** | **Boot receiver, keepalive receiver, exact alarm, wake locks, heartbeat, scanner restart** | Foreground service and wake lock; boot receiver is reminder-only | Accessibility + overlay FGS; no boot receiver declared | No app FGS/boot receiver for automation |
| Multi-app breadth | Ola and Rapido | Ola, Rapido, Uber, Porter | Ola, Rapido, Uber, Namma Yatri, Porter, Jugnoo | Ola, Rapido, Uber, Porter, Swiggy | Fourteen ride/delivery platforms |
| Blind coordinate risk | Low by design; known Accept centers only | Moderate; app-specific geometry exists | Moderate; geometric/card strategies | Moderate; cross-app coordinate replay | **High**; several fixed screen-fraction fallbacks |
| Hot-path complexity | **Very high** | Very high | Very high | Low/medium but inefficient | Medium/high |

## Per-application findings

### 1. FastClicker 2.19.55

**Verdict:** strongest competitor and most relevant reference.

Confirmed behavior:

- Requests foreground-service special use, boot receive, wake lock, exact alarms, battery-optimization exemption, and Shizuku.
- Accessibility service receives window, content, notification, text, focus, and click events with zero notification timeout.
- Uses explicit Rapido and Ola view-ID lists in addition to multilingual text labels.
- Creates a priority-10 `HandlerThread` named `FastClickerRideScanner`; coalesced follow-up scans use 8 ms delays.
- Uses `ACTION_CLICK`, 1 ms accessibility gestures, parent click fallback, and Shizuku `input swipe x y x y duration`.
- Shizuku press duration is clamped to at least 20 ms.
- Its readable notification-action path sends a reject/decline action when filters fail; a notification Accept-action path was not established.
- Has controlled retries and post-click verification for Ola.
- Detects `ACCEPT IN 1`, schedules a timer near 960 ms, and then polls readiness at 16 ms before a serialized tap.
- Uses heartbeat storage, wake locks, alarms, boot and keepalive receivers, and scanner-thread restart after an uncaught exception.

Important limitation:

- No `NotificationListenerService` is declared. Its notification reaction starts from accessibility `TYPE_NOTIFICATION_STATE_CHANGED`, which is generally less independent than Ridio's dedicated NLS path.
- The implementation is large and heavily obfuscated. It also performs substantial logging, filter parsing, UI notification, and history work around the race engine.

What Ridio should learn:

- Copy the **idea**, not the code: countdown-edge scheduling, isolated scanner queue, strong view-ID profiles, and explicit lifecycle recovery.

### 2. RideClicker 1.1.4

**Verdict:** technically sophisticated click/finder implementation, but lower static confidence because its main event dispatcher did not decompile.

Confirmed behavior:

- Accessibility is restricted to specific driver packages and receives window/content/notification events with zero timeout.
- Uses node/card/sibling geometry and fare/distance parsing rather than only searching for the word Accept.
- Dispatches a 1 ms center gesture with small randomized coordinate jitter.
- Some paths execute `ACTION_CLICK` and accessibility gestures in parallel, guarded by an `AtomicBoolean` winner.
- Climbs up to six clickable parents.
- Contains app-specific gesture durations and retry schedules.
- Requests Shizuku and `INJECT_EVENTS`, but the readable Shizuku behavior is primarily used to modify developer-option/ADB settings.
- Includes a foreground service, wake lock, and battery-optimization handling. Its boot receiver shows a reminder when accessibility is off; it does not restart automation.

Important limitations:

- The requested `INJECT_EVENTS` permission is signature-only for ordinary apps and does not itself provide input injection.
- A Shizuku-based click injector was not established from readable code.
- Extensive hot-path diagnostic logging and broad geometric parsing can cost time on low-end phones.

What Ridio should learn:

- Keep the parallel-actuator concept: node action and one independent gesture can race, but only after target validation.
- Add measured `found -> dispatch` telemetry like RideClicker's timing logs, without retaining verbose production logs.

### 3. Ride Accepter Pro Max 1.0

**Verdict:** feature-rich UI but the weakest primary race path among the four references.

Confirmed behavior:

- Debounces screen scans by 80 ms.
- On every scan, collects every interactive window, recursively collects all leaf text/content descriptions, parses ride details, then recursively searches for exact Accept variants.
- Performs a clickable-node/ancestor `ACTION_CLICK`, followed by a fixed 50 ms gesture at the node center.
- Stores known coordinates in memory. Optional multi-app mode builds simultaneous shell `input tap` commands through Shizuku.
- Notification handling parses filters and opens the notification `contentIntent`; no direct notification Accept-action execution was found.
- Initializes Text-to-Speech in the accessibility service and performs extensive logging/history work.

Important limitations:

- The 80 ms debounce and complete multi-window text collection happen before the first UI click.
- Shizuku shell process creation and `waitFor()` are expensive compared with direct `IInputManager` injection.
- `input tap` normally produces no stdout, but its helper treats non-null stdout as success; success reporting may therefore be unreliable even when the command executes.
- No target-specific Ola unlock mechanism was found.
- APK is marked `debuggable=true`.

What Ridio should learn:

- Location filtering and data-driven supported-app definitions may be useful product features, but none of this belongs before Ridio's first strike.

### 4. RiderAccepter 1.6

**Verdict:** best data-driven multi-platform structure, but not a faster Rapido/Ola race engine.

Confirmed behavior:

- Defines per-platform configuration for Ola, Uber, Rapido, Namma Yatri, inDrive, BluSmart, delivery apps, Porter, and Amazon Flex.
- Tries a notification Accept action before opening the partner app and beginning screen retries.
- Runs a 35 ms retry loop, with 6 s standard and 18 s Rapido retry windows.
- Uses text/action nodes, visible-window scoring, wide primary buttons, fixed screen-fraction fallbacks, 7 ms tap gestures, and target-specific swipe logic.
- Verifies after 1 second and classifies accepted, missed, or still-visible states.
- ML Kit OCR is bundled, but actual OCR calls are in payment-proof processing in `MainActivity`, not the ride-accept hot path.

Important limitations:

- No dedicated NLS, Shizuku input, boot receiver, or automation foreground service was found.
- Several fallbacks tap fixed screen fractions, for example Ola near `(60% width, 50% height)` and generic Accept near `(50% width, 84% height)`, with offset retries. This increases false-tap risk across resolutions, insets, rotations, and UI experiments.
- Its 35 ms retry loop is useful for late paint but cannot beat a valid cached strike fired immediately from NLS.
- APK is marked `debuggable=true`.

What Ridio should learn:

- Move labels, package IDs, detection rules, and safe actuation policy into data-driven per-platform profiles.
- Do not adopt blind screen-fraction tapping or OCR on the click hot path.

## Where Ridio is already better

| Ridio advantage | Why it matters |
|---|---|
| Dedicated notification listener | Receives a ride signal independently of accessibility window delivery and can replay active notifications after rebind |
| Predictive cached-coordinate strike | Can attempt a valid Rapido pixel before a new accessibility tree exists |
| Direct Shizuku `IInputManager` injection | Avoids starting and waiting for a shell process for every injected event |
| Partner-specific click routing | Rapido uses node/short gesture; Ola uses privileged duration-bearing input after unlock |
| Exact-center policy and bubble rejection | Reduces destructive false taps and partner-app reopen loops |
| Accept-gone verification and short back-to-back cooldown | Handles retries and rapid sequential offers |
| Sticky engine process plus watchdog | Strong survival foundation on OEMs that suppress events |

## What Ridio is missing or should improve

| Gap | Evidence/risk | Recommended change |
|---|---|---|
| Ola edge timing is less explicit than FastClicker's | FastClicker reacts to `ACCEPT IN 1` with a 960 ms edge timer and 16 ms readiness loop | Add a countdown-edge scheduler that combines label countdown, first-card-seen time, and unlock confirmation |
| One very large service owns almost everything | Detection, filtering, finding, state, actuation, verification, OEM recovery, and reporting share `AutoClickerService` | Extract components without behavior change: `RaceCoordinator`, `AcceptFinder`, `ClickRouter`, `AcceptCache`, `DeviceProfile`, `RaceMetrics` |
| 1 ms continuous hunt can create queue/CPU pressure | On weak phones, repeated tree walks can delay the very click they are intended to accelerate | Keep event-driven immediate scan; back off polling based on measured tree-walk duration and queue lateness |
| Predictive cache is accepted for up to 24 hours | App updates, navigation mode, resolution/insets, or UI experiments can move Accept | Key cache by package version, orientation, display size/insets, window type, and layout fingerprint; invalidate on mismatch |
| OEM profile is mostly manufacturer-based | Devices and app versions fail differently even under the same brand | Adapt from observed outcomes: gesture rejection, empty trees, node-click success, and Accept-gone latency |
| Production race metrics are incomplete | Static code cannot prove market speed | Record bounded per-ride timestamps: signal, cached strike, node found, action sent, gesture sent, Accept gone, and failure reason |
| Service main queue still performs expensive work | Window walks, filter parsing, retries, and logs can serialize | Give first actuation strict priority; move parsing/history/notifications after strike and put bounded scans on a dedicated worker |
| Hidden API injection is brittle | `IInputManager` reflection may change across Android releases | Keep accessibility and shell fallbacks, feature-detect injection at startup, and record which actuator actually succeeds |
| Some configured filters are not enforced | `filterMode` and `maxDrop` are stored but not read by the native race service | Remove these settings or wire them through one tested `OfferGate`; never imply a filter is active when it is not |
| Ola notification arming is permissive | A non-ongoing Ola notification can arm even without a strict ride cue | Require a scored cue set and monitor false-arm rate while retaining image-only alert support |
| NLS signal depends on a live accessibility singleton | `onRideSignal` returns when `AutoClickerService.sInstance` is null | Persist a pending signal in the engine process and consume it immediately when accessibility reconnects |
| Filters can sacrifice the notification fast path | Rapido notification Accept is skipped when min-fare or pickup filtering is enabled | Make this trade-off explicit in UI and measure filtered versus unfiltered signal-to-click latency |

## Recommended implementation order

### P0 — Measure before changing behavior

Add a bounded native race trace with monotonic timestamps:

`signal -> cached strike -> fast root found -> node click -> gesture/inject -> Accept gone`

Report p50, p90, p99, false-tap rate, and successful rides before/after each change. Test at least:

- stock Android / Pixel-like;
- Samsung One UI;
- Xiaomi/Redmi/POCO;
- OPPO/Realme/OnePlus;
- Vivo/iQOO;
- 2–4 GB low-RAM device;
- ride 1, ride 5, and ride 20 without reopening Ridio.

### P0 — Make predictive cache safe without slowing it

Retain the immediate cached strike, but only when these match the learned record:

- package and package version;
- portrait/landscape;
- real display width/height;
- status/navigation-bar insets;
- overlay versus in-app window;
- recent validated Accept bounds/layout fingerprint.

Invalidate immediately after app update, orientation/display change, self-UI foreground without a validated overlay, or repeated Accept-gone failures.

### P1 — Adopt FastClicker's best Ola idea

When the accessibility text transitions to `ACCEPT IN 1`:

1. cache the validated button bounds;
2. schedule the expected zero edge around 950–1000 ms;
3. begin a short 8–16 ms readiness loop near the edge;
4. strike only when the label is plain Accept or the independently measured unlock deadline passes;
5. verify and retry once.

This should complement, not replace, Ridio's 5-second fallback.

### P1 — Split and prioritize the engine

Target structure:

```text
native-android/src/engine/
  RaceCoordinator.java
  RaceSignal.java
  RaceMetrics.java
  AcceptCache.java
native-android/src/finder/
  AcceptFinder.java
  WindowPolicy.java
native-android/src/actuator/
  ClickRouter.java
  AccessibilityActuator.java
  ShizukuActuator.java
native-android/src/profile/
  PartnerProfile.java
  DeviceProfile.java
```

The first refactor must be behavior-preserving. Do not mix architecture changes with new timing values.

### P1 — Data-driven partner profiles

Borrow RiderAccepter's configuration idea and FastClicker's view-ID depth:

- package aliases;
- Accept/reject/countdown labels by locale;
- known view IDs;
- safe bounds/window rules;
- supported actuator order;
- unlock policy;
- retry/verification policy;
- package-version overrides.

Profiles must never permit a blind coordinate unless it was validated and learned on that exact device/layout.

### P2 — Adaptive scan scheduling

Do not use a permanent 1 ms tree-walk loop on every phone. Keep instant event-triggered work, then select 2/4/8/16/32 ms follow-ups using:

- last tree-walk duration;
- main-queue lateness;
- recent empty-tree count;
- current race phase;
- target OEM/app version;
- whether the predictive spray is already active.

### P2 — Do not copy these competitor patterns

- Do not add OCR to the first-click path.
- Do not scan and log the entire accessibility tree before the first strike.
- Do not use fixed screen fractions as a general Accept fallback.
- Do not create a Shizuku shell process for every Rapido tap when direct injection is available.
- Do not assume a requested privileged permission such as `INJECT_EVENTS` is actually granted.
- Do not mark a ride accepted merely because `dispatchGesture()` or `performAction()` returned `true`.

## Final recommendation

Keep Ridio's current strategic core:

1. dedicated NLS signal;
2. safe predictive cached strike;
3. immediate live-node `ACTION_CLICK`;
4. independent gesture/Shizuku actuator;
5. partner-specific routing;
6. Accept-gone verification;
7. persistent engine recovery.

The next highest-value work is **not more tap spraying**. It is:

1. add reliable latency and outcome metrics;
2. make the cached strike layout-safe;
3. add FastClicker-style Ola countdown-edge scheduling;
4. isolate scanning from first actuation;
5. refactor the large service into testable components;
6. validate on a repeated-ride OEM matrix.

Based on static evidence, Ridio is probably already ahead for Rapido's first possible tap. FastClicker is the benchmark to beat for Ola timing and lifecycle hardening. Only controlled device testing can establish the actual winner.

## Evidence map

Reference paths below are relative to each JADX output folder.

| Finding | Decompiled evidence |
|---|---|
| FastClicker event config and app packages | `resources/res/xml/accessibility_service_config.xml:5-10` |
| FastClicker view IDs and labels | `sources/com/fastclicker/RideAutoAcceptService.java:294-313` |
| FastClicker scanner thread and 8 ms coalescing | `RideAutoAcceptService.java:3054-3128`, `6020-6041`; `sources/p000x/m30.java:73-114` |
| FastClicker 1 ms gesture / Shizuku press | `RideAutoAcceptService.java:3040-3048`, `5760-5790`; `sources/p000x/AbstractC0357ix.java:40-68` |
| FastClicker Ola countdown edge | `RideAutoAcceptService.java:3840-3908`, `5813-5880` |
| FastClicker boot/keepalive declaration | `resources/AndroidManifest.xml:69-111` |
| RideClicker event configuration | `resources/res/xml/accessibility_service_config.xml:4-9` |
| RideClicker 1 ms gesture | `sources/com/rideclicker/AutomationService.java:438-465` |
| RideClicker parallel action and gesture | `AutomationService.java:1270-1511` |
| RideClicker boot receiver is reminder-only | `sources/com/rideclicker/BootReceiver.java:22-51` |
| Pro Max 80 ms debounce | `sources/com/example/AutoAcceptService.java:57-73`, `92-135` |
| Pro Max full-window scan before click | `AutoAcceptService.java:238-455` |
| Pro Max node + 50 ms gesture | `AutoAcceptService.java:470-510` |
| Pro Max shell multi-click | `sources/com/example/MultiAppClicker.java:48-70`; `ShizukuManager.java:80-150` |
| RiderAccepter profiles and timing constants | `sources/com/algomatrix/rideracceptor/RideAutomationService.java:64-149` |
| RiderAccepter 35 ms screen retry | `RideAutomationService.java:360-384` |
| RiderAccepter notification action | `RideAutomationService.java:1768-1846` |
| RiderAccepter fixed-coordinate fallbacks | `RideAutomationService.java:2064-2270` |
| RiderAccepter 1 s verification | `RideAutomationService.java:500-610` |
| RiderAccepter OCR is payment-only | `sources/com/algomatrix/rideracceptor/MainActivity.java:2920-2950` |
| Ridio dedicated NLS | `native-android/src/RideAlertListener.java:25-46`, `78-146` |
| Ridio predictive cache/spray | `native-android/src/AutoClickerService.java:1373-1455` |
| Ridio notification action and race pipeline | `AutoClickerService.java:3233-3418` |
| Ridio Rapido/Ola router | `AutoClickerService.java:4610-4820` |
| Ridio direct privileged injection | `native-android/src/ShizukuInput.java:27-52`, `228-405` |
| Ridio sticky keepalive | `native-android/src/EngineKeepAlive.java:24-103` |
