# Ridio Improvement and Implementation Plan

Date: 2026-09-11  
Status: Proposed implementation roadmap  
Input analysis: `docs/REFERENCE_TAPPER_ANALYSIS.md`

## 1. Objective

Improve Ridio's ride-detection and Accept pipeline without sacrificing accuracy, subscription controls, or long-session reliability.

The implementation should optimize four measurable outcomes:

| Outcome | Definition |
|---|---|
| Speed | Time from the earliest ride signal to the first valid Accept attempt |
| Success | Percentage of eligible ride offers confirmed as accepted |
| Safety | False taps, wrong-window taps, duplicate accepts, and filtered rides accepted incorrectly |
| Survival | Continued operation after many rides, screen lock, UI swipe-away, and OEM background pressure |

No implementation can guarantee that Ridio is first on every device or network. The engineering target is the fastest safe path supported by Android, verified through repeatable measurements.

## 2. Current baseline to preserve

These capabilities are already competitive and must not be removed during refactoring:

1. Dedicated `NotificationListenerService` as the earliest signal.
2. Accessibility notification and window events as backup signals.
3. Rapido cached-coordinate predictive strike before the accessibility tree paints.
4. Notification Accept action when available and allowed by filters.
5. Live Accept-node `ACTION_CLICK`.
6. Accessibility gesture fallback.
7. Direct Shizuku `IInputManager` injection with shell fallback.
8. Separate Rapido and Ola actuation rules.
9. Accept-gone verification and stuck-state recovery.
10. Bubble, self-UI, and invalid-bounds rejection.
11. Sticky `:engine` process, foreground service, NLS rebind, and active-notification replay.

## 3. Problems to solve

| ID | Problem | Risk | Priority |
|---|---|---|---|
| P-01 | No reliable end-to-end latency baseline | Changes may feel faster while becoming slower | P0 |
| P-02 | Predictive coordinates can remain valid for 24 hours | Stale coordinates can tap the wrong UI | P0 |
| P-03 | NLS signal is dropped when the accessibility singleton is unavailable | Earliest ride signal can be lost during reconnect | P0 |
| P-04 | `AutoClickerService` owns too many responsibilities | Timing changes are difficult to test and regressions are hard to isolate | P0 |
| P-05 | Permanent 1 ms tree polling can overload weak devices | CPU/queue pressure may delay the real click | P1 |
| P-06 | Ola timing relies heavily on countdown/fixed-time interpretation | Tap can be early or late around the unlock edge | P1 |
| P-07 | OEM tuning is mostly manufacturer-based | Same-brand devices and app versions behave differently | P1 |
| P-08 | `filterMode` and `maxDrop` are stored but not enforced | UI may promise behavior the engine does not provide | P1 |
| P-09 | Rapido notification Accept is skipped when filters are enabled | Filtered mode can lose the earliest path | P1 |
| P-10 | Ola notification classification is permissive | Non-ride notifications may arm the clicker | P1 |
| P-11 | Hidden input APIs can change across Android versions | Shizuku injection may fail without a clear fallback reason | P2 |
| P-12 | Existing documentation contains stale timing descriptions | QA can validate the wrong behavior | P0 |

## 4. Implementation principles

1. **Measure first.** Do not tune delays without before/after data.
2. **One behavior change per phase.** Refactoring and timing changes must not share a release.
3. **First actuation has priority.** History, UI events, TTS, logs, and analytics happen afterward.
4. **Never trust return values alone.** `ACTION_CLICK=true` or `dispatchGesture=true` is not acceptance confirmation.
5. **Never add a general blind coordinate fallback.** A coordinate must be learned and validated for the current layout.
6. **Keep partner-specific behavior.** Rapido and Ola have different acceptance physics.
7. **Fail safely.** Missing data may fail open for user-selected filters only when that policy is explicit.
8. **Use monotonic time.** Race timing must use `SystemClock.uptimeMillis()` or elapsed realtime, not wall-clock time.
9. **Keep Java native code on the hot path.** React Native and network APIs must not participate in a ride-time decision.
10. **Do not copy proprietary decompiled code.** Reimplement general techniques from observed behavior.

## 5. Target architecture

The AccessibilityService remains the Android entry point, but delegates to focused components.

```text
native-android/src/
  AutoClickerService.java              Android AccessibilityService adapter
  RideAlertListener.java               NotificationListenerService adapter
  EngineKeepAlive.java                 Engine foreground-service lifecycle
  AutoClickerConfig.java               Compatibility facade during migration

  engine/
    RaceCoordinator.java               State machine and signal ordering
    RaceSignal.java                    Immutable ride-signal model
    RaceState.java                     IDLE/ARMED/STRIKING/VERIFYING/COOLDOWN
    PendingSignalStore.java            Signal handoff while a11y reconnects

  finder/
    AcceptFinder.java                  Fast root, ID, text, and bounded search
    AcceptCandidate.java               Node, package, bounds, confidence
    WindowPolicy.java                  Bubble/self/window safety rules

  actuator/
    ClickRouter.java                   Partner-specific actuator order
    AccessibilityActuator.java         ACTION_CLICK and gesture
    ShizukuActuator.java               Direct inject and shell fallback
    ActuationResult.java               Sent/rejected/completed transport result

  profile/
    PartnerProfile.java                Labels, IDs, unlock and retry policy
    DeviceProfile.java                 Runtime device capability profile
    ProfileRegistry.java               Package/version profile lookup

  cache/
    AcceptPointCache.java              Validated point and layout identity
    LayoutFingerprint.java             Display/app/window compatibility key

  filter/
    OfferGate.java                     One source of truth for ride filters
    OfferFacts.java                    Fare, pickup, drop and confidence

  metrics/
    RaceTrace.java                     Per-ride monotonic timestamps
    RaceMetricsStore.java              Bounded local ring buffer
    RaceOutcome.java                   Accepted/missed/failed/filtered/unknown
```

### Dependency direction

```text
NLS / Accessibility adapters
            |
            v
     RaceCoordinator
      /     |      \
 Finder  OfferGate  ClickRouter
   |                    |
 Cache             Actuators
      \              /
         RaceTrace
```

UI and React Native may read status and metrics, but they must not be dependencies of `RaceCoordinator`.

## 6. Delivery phases

### Phase 0 — Freeze the baseline and align documentation

**Goal:** establish exactly how the current APK behaves before changing it.

Tasks:

1. Record the current values directly from native source:
   - hunt intervals;
   - predictive spray duration and tap count;
   - cache age;
   - Ola unlock delay and grace;
   - verification window and restrikes;
   - cooldown and watchdog periods.
2. Correct stale values in:
   - `docs/ACCEPT_EDGE_CASES.md`;
   - `docs/DEVICE_OEM_RELIABILITY.md`;
   - `src/native/README.md`.
3. Define a release identifier containing:
   - Ridio version;
   - native engine version;
   - Rapido/Ola package versions;
   - Android version and device model.
4. Capture at least 20 eligible offers on the existing engine before tuning.

Deliverables:

- baseline timing report;
- current constants checklist;
- known device/app-version matrix;
- reproducible log collection procedure.

Exit criteria:

- every test result can be tied to an APK and partner-app version;
- documentation matches source;
- no engine behavior has changed.

### Phase 1 — Add race tracing

**Goal:** measure the actual critical path with low overhead.

New component:

- `native-android/src/metrics/RaceTrace.java`
- `native-android/src/metrics/RaceMetricsStore.java`

Capture these timestamps:

| Event | Meaning |
|---|---|
| `signalReceived` | NLS or accessibility notification callback entered |
| `signalQualified` | Ride classifier accepted the signal |
| `pendingIntentSent` | Notification Accept action was sent |
| `predictiveStrikeSent` | First cached-coordinate attempt left Ridio |
| `fastFindStarted` | Active-root fast search began |
| `acceptFound` | Validated Accept candidate was found |
| `nodeClickSent` | `ACTION_CLICK` was attempted |
| `gestureSent` | Accessibility gesture was accepted for dispatch |
| `injectSent` | Shizuku direct/shell actuation was attempted |
| `acceptGone` | Accept disappeared during verification |
| `outcomeKnown` | Accepted, missed, failed, filtered, or unknown |

Rules:

- Use monotonic timestamps.
- Store no passenger name, phone number, pickup address, or drop address.
- Keep a bounded ring buffer, such as the latest 100 races.
- Disable verbose per-poll logging in production.
- Emit one compact summary after the outcome is known.

Metrics:

- signal-to-first-attempt p50/p90/p99;
- signal-to-node-found p50/p90/p99;
- node-found-to-click p50/p90/p99;
- Accept-gone latency;
- successful accepts;
- false arms;
- false taps;
- stale-cache blocks;
- actuator success by type;
- ride 1 versus ride 5/10/20.

Exit criteria:

- tracing adds no more than 1 ms p90 overhead on the test devices;
- every attempt has one trace ID;
- duplicate NLS/a11y signals merge into one race;
- logs contain no sensitive ride data.

### Phase 2 — Safe predictive cache

**Goal:** retain Ridio's pre-tree speed while preventing stale-coordinate taps.

New components:

- `cache/AcceptPointCache.java`
- `cache/LayoutFingerprint.java`

Each cache entry should include:

| Field | Purpose |
|---|---|
| Package name | Prevent cross-app use |
| Package version code | Invalidate after partner-app update |
| Display width and height | Prevent reuse on incompatible resolution |
| Orientation | Prevent portrait/landscape mismatch |
| Display insets | Account for status/navigation bars |
| Window category | Separate overlay from full in-app card |
| Accept bounds and center | Preserve validated target |
| Learned and last-confirmed times | Enforce freshness |
| Success/failure counters | Disable unreliable entries |
| Layout fingerprint | Detect incompatible UI structure |

Validation policy:

1. Load cache when the engine starts.
2. Permit a predictive strike only when required identity fields match.
3. Refresh the cache after finding a live Accept.
4. Mark it confirmed only when the Accept-gone verifier supports success.
5. Invalidate after:
   - partner-app update;
   - display/orientation/inset change;
   - point enters a bubble/self window;
   - repeated unconfirmed strikes;
   - bounds change beyond tolerance;
   - explicit user cache reset.

Migration:

- Read the existing `tap_x_*` and `tap_y_*` keys once.
- Treat migrated entries as unconfirmed.
- Require one live-node validation before enabling predictive use.
- Remove legacy keys only after successful migration.

Exit criteria:

- no blind default point is introduced;
- incompatible display/app versions never use old points;
- valid learned points preserve current first-attempt latency;
- stale-layout test produces a blocked strike, not a tap.

### Phase 3 — Reliable signal handoff

**Goal:** never lose an NLS signal because AccessibilityService is temporarily disconnected.

New components:

- `engine/RaceSignal.java`
- `engine/PendingSignalStore.java`

Process:

1. `RideAlertListener` classifies the notification.
2. It stores a minimal pending signal before calling the service singleton.
3. If `AutoClickerService` is available, it consumes immediately.
4. If unavailable, the latest valid signal remains pending for a short TTL.
5. `onServiceConnected()` consumes an unexpired pending signal.
6. Signal IDs prevent the NLS and accessibility copies from creating two races.

Stored fields:

- package;
- monotonic receive time;
- notification ID/key hash;
- classifier reason;
- parsed fare/pickup facts when available;
- expiry time.

Do not persist:

- full notification text;
- passenger details;
- route addresses.

Exit criteria:

- NLS arriving before accessibility reconnect is consumed after reconnect;
- expired notifications are never replayed as live rides;
- a duplicated NLS/a11y notification produces one race;
- active-notification replay remains functional.

### Phase 4 — Behavior-preserving engine extraction

**Goal:** split the large service without changing timing or click decisions.

Extraction order:

| Step | Extract | Existing responsibility |
|---|---|---|
| 4.1 | `RaceState` and `RaceCoordinator` | Phase transitions, cooldown, stuck recovery |
| 4.2 | `WindowPolicy` | Bubble, self-UI, overlay and bounds guards |
| 4.3 | `AcceptFinder` | Fast text, view ID, bounded BFS and multi-window search |
| 4.4 | `ClickRouter` | Rapido versus Ola routing |
| 4.5 | `AccessibilityActuator` | Node click and accessibility gesture |
| 4.6 | `ShizukuActuator` | Existing `ShizukuInput` facade |
| 4.7 | `OfferGate` | Fare and distance policy |
| 4.8 | `PartnerProfile` | Packages, labels, IDs and timing policy |

Rules:

- Move code before rewriting it.
- Preserve existing constants and ordering.
- Preserve existing logs until trace equivalence is proven.
- Add package-private tests around every extracted decision.
- Keep `AutoClickerService` as a thin adapter.
- Keep `AutoClickerConfig` as a compatibility facade until JS/native callers migrate.

Equivalence tests:

- same event sequence produces the same phase transitions;
- same tree fixture produces the same Accept candidate;
- same filters produce the same allow/skip result;
- same package routes to the same actuator;
- same failure sequence produces the same retry/cooldown behavior.

Exit criteria:

- latency and success rates are statistically unchanged from Phase 1;
- no public JS bridge behavior changes;
- no new permission is required;
- `AutoClickerService` contains adapters and lifecycle code, not parsing/actuation internals.

### Phase 5 — Data-driven partner profiles and filter repair

**Goal:** make app updates safer and ensure every exposed filter is real.

Example profile responsibilities:

```text
RapidoProfile
  packages
  acceptLabels
  acceptViewIds
  overlayRules
  actuatorOrder
  retryPolicy
  verificationPolicy

OlaProfile
  packages
  acceptLabels
  countdownLabels
  acceptViewIds
  unlockPolicy
  actuatorOrder
  retryPolicy
  verificationPolicy
```

Filter decisions must come from one `OfferGate`:

| Setting | Decision required |
|---|---|
| Minimum fare | Enforce, or remove from UI |
| Maximum pickup | Enforce, or remove from UI |
| Maximum drop | Implement and test, or remove from storage/UI |
| Filter mode | Implement documented semantics, or remove |
| Unknown fare/distance | Explicit user-visible fail-open/fail-closed policy |

Recommended unknown-data policy:

- Auto-accept with no filters: allow.
- A filter enabled but its required value is unknown: configurable policy.
- Default to fail open only if the UI clearly states “accept when value cannot be read.”

Filtered Rapido trade-off:

- Notification Accept is fastest but may not expose enough facts.
- UI-card parsing is slower but permits accurate filtering.
- Expose two modes:
  - **Fastest:** notification action may accept before complete filtering.
  - **Strict filters:** wait for validated card facts.

Exit criteria:

- every visible filter has native tests and actual enforcement;
- profiles are selected by canonical package and aliases;
- profile data cannot enable unsafe blind tapping;
- users understand Fastest versus Strict-filter behavior.

### Phase 6 — Ola countdown-edge scheduler

**Goal:** strike as close as safely possible to the actual Ola unlock edge.

New component:

- `profile/OlaUnlockScheduler.java`

Inputs:

- first card-seen monotonic time;
- NLS receive time;
- parsed `ACCEPT IN N` value;
- transition time between countdown labels;
- plain Accept visibility;
- cached validated bounds;
- recent device-specific unlock observations.

Algorithm:

1. Sight and validate the Ola Accept/countdown node.
2. Cache bounds and ride fingerprint.
3. Track countdown transitions.
4. At `ACCEPT IN 1`, predict the zero edge from observed transitions.
5. Schedule one near-edge check around 950–1000 ms.
6. Start a short 8–16 ms readiness loop only near the predicted edge.
7. Strike when:
   - the label becomes plain Accept; or
   - the validated fallback deadline is reached.
8. Perform one controlled retry only if the same ride and Accept remain visible.
9. Stop immediately on ride change, missed state, expired card, or success evidence.

Safety:

- Never reuse bounds from a different ride fingerprint.
- Never use this scheduler for Rapido.
- Never run an unbounded readiness loop.
- Keep the existing 5-second fallback until field results prove a better model.

Exit criteria:

- no early clicks before unlock in test traces;
- unlock-to-first-attempt p90 improves over baseline;
- first-attempt failure has at most one controlled retry;
- same-card fingerprint is checked before each strike.

### Phase 7 — Adaptive scanner and actuator capability model

**Goal:** reduce low-end queue pressure while maintaining immediate event response.

Scanner rules:

1. Accessibility events always trigger an immediate fast-root search.
2. Fast search checks known labels/IDs before any broad tree walk.
3. Broad multi-window search is coalesced.
4. Follow-up interval adapts among 2, 4, 8, 16, and 32 ms.
5. Back off when:
   - tree walks exceed the interval;
   - queue lateness increases;
   - repeated scans return identical empty trees;
   - a predictive spray already occupies the gesture channel.
6. Tighten temporarily when:
   - a valid ride signal is active;
   - the target window is visible;
   - an Accept/countdown candidate was recently sighted.

Actuator capability state:

| Capability | Runtime state |
|---|---|
| Node `ACTION_CLICK` | unknown / reliable / unreliable |
| Accessibility gesture | ready / occupied / rejected |
| Shizuku binder | missing / stopped / denied / ready |
| Direct input manager | unknown / ready / unavailable |
| Shell fallback | ready / failed |

Do not infer capability only from manufacturer. Learn from current-session outcomes while retaining conservative OEM defaults.

Exit criteria:

- no uncontrolled 1 ms tree-walk loop;
- low-RAM CPU and queue lateness improve;
- immediate event-to-fast-find latency does not regress;
- actuator fallbacks produce a recorded reason.

### Phase 8 — Ride classification hardening

**Goal:** reduce false Ola arms without missing opaque/image-only ride alerts.

Replace broad boolean matching with a scored classifier:

| Evidence | Example score |
|---|---:|
| Explicit ride-request phrase | +5 |
| Accept notification action | +5 |
| Fare and pickup/distance together | +4 |
| Full-screen/high-priority non-ongoing alert | +2 |
| Known ride notification channel/ID pattern | +2 |
| Online/status/earnings phrase | -6 |
| Foreground-service ongoing notification | -6 |
| Recent duplicate notification key | -4 |

Thresholds:

- high score: arm immediately;
- medium score: arm finder only, but do not predictive-tap;
- low/negative score: ignore.

The actual score values must be derived from captured false-arm and real-ride samples, not copied blindly from this table.

Exit criteria:

- false Ola arms decrease from baseline;
- image-only ride recall does not materially regress;
- classifier reason appears in the race trace;
- Rapido's stricter behavior remains unchanged.

### Phase 9 — Verification and release hardening

**Goal:** prove the integrated engine before broad deployment.

Verification states:

- `ACCEPT_SENT`;
- `ACCEPT_GONE`;
- `PARTNER_ACCEPTED_UI`;
- `MISSED_OR_TAKEN`;
- `CARD_EXPIRED`;
- `CLICK_REJECTED`;
- `UNKNOWN`.

Never map `ACCEPT_SENT` directly to accepted.

Release gates:

| Gate | Required result |
|---|---|
| Static build | Clean Android release build |
| Unit tests | All finder/filter/state/profile tests pass |
| Regression fixtures | Existing Rapido/Ola trees produce expected decisions |
| Baseline speed | No p90 regression for unfiltered Rapido |
| Safety | Zero known wrong-window taps in test matrix |
| Repeated rides | Ride 1, 5, 10, and 20 remain operational |
| Screen lock | Engine processes a signal after five minutes locked |
| Recents swipe | Engine survives where OEM permits; UI removal does not corrupt state |
| Reconnect | NLS and accessibility rebind/replay do not duplicate a race |
| Low RAM | No runaway polling, queue buildup, or crash |

## 7. Testing strategy

### Unit tests

Test pure native logic without Android UI where possible:

- ride-signal deduplication;
- phase transitions;
- stale-state recovery;
- cache fingerprint matching;
- filter decisions and unknown-data policy;
- partner profile selection;
- countdown prediction;
- classifier scoring;
- retry limits;
- outcome classification.

### Accessibility-tree fixtures

Capture sanitized node-tree fixtures for:

- Rapido in-app card;
- Rapido overlay card;
- Rapido bubble only;
- Rapido missed card;
- Rapido along-route offer;
- Ola countdown values 5 through 1;
- Ola plain Accept;
- Ola accepted screen;
- Ola expired/missed screen;
- self UI;
- notification shade;
- app-version layout variants.

Fixtures must remove passenger and route information.

### Device matrix

| Tier | Devices |
|---|---|
| Stock | Pixel or Android One |
| Samsung | Low/mid-range Galaxy A or M |
| Xiaomi | Redmi/POCO with MIUI or HyperOS |
| ColorOS | OPPO, Realme, or current OnePlus |
| Vivo | Vivo or iQOO |
| Transsion | Infinix, Tecno, or itel |
| Low RAM | 2–4 GB device with four or fewer cores |

### Scenario matrix

1. Ridio UI foreground.
2. Partner app foreground.
3. Another app foreground with ride overlay.
4. Screen locked.
5. Ridio swiped from Recents.
6. Accessibility service restarted.
7. NLS disconnected and rebound.
8. Shizuku installed/running/granted.
9. Shizuku missing, stopped, and denied.
10. Filters off.
11. Fastest mode.
12. Strict-filter mode with known values.
13. Strict-filter mode with unknown values.
14. Twenty sequential ride events.
15. Partner app updated after a coordinate was cached.
16. Orientation/display/inset change after cache creation.

## 8. Performance budgets

Initial budgets must be validated against the Phase 1 baseline.

| Segment | Proposed budget |
|---|---:|
| NLS callback to signal qualification | ≤ 2 ms p90 |
| Qualified signal to first cached attempt | ≤ 5 ms p90 when cache is valid |
| Live node found to `ACTION_CLICK` | ≤ 3 ms p90 |
| Fast-root search | ≤ 8 ms p90 |
| Broad tree/window search | ≤ 20 ms p90 |
| Trace overhead | ≤ 1 ms p90 |
| Main-queue lateness during active race | ≤ 8 ms p90 |

These are engineering budgets, not guaranteed booking times. Partner-app rendering and network/server acceptance remain outside Ridio's control.

## 9. Rollout process

Use staged native-engine versions:

| Stage | Audience | Purpose |
|---|---|---|
| Internal | Development devices | Validate traces and safety |
| Alpha | Small known-device set | Verify target app versions and OEM behavior |
| Beta | 5–10% of active users | Detect device-specific regressions |
| Expanded | 25–50% | Validate p90/p99 and long-session reliability |
| General | All eligible users | Release after gates pass |

Required controls:

- local master kill switch;
- server-controlled engine feature flags where already supported;
- separate flags for safe cache, Ola edge scheduler, adaptive scanner, and classifier;
- ability to revert timing profiles without replacing the APK;
- engine version included in support diagnostics.

Rollback triggers:

- increased wrong-window taps;
- reduced Rapido success rate;
- increased Ola early-click rate;
- repeated-rides regression;
- engine crash or inaccessible service;
- excessive CPU/battery usage;
- increased filtered-ride violations.

## 10. Suggested work packages

Each work package should be independently reviewable.

| Work package | Contents | Depends on |
|---|---|---|
| WP-01 Baseline | Constants, docs, test protocol | None |
| WP-02 Metrics | Race trace and bounded store | WP-01 |
| WP-03 Safe cache | Fingerprint and migration | WP-02 |
| WP-04 Signal handoff | Pending signal and dedup | WP-02 |
| WP-05 State extraction | Coordinator and tests | WP-02 |
| WP-06 Finder extraction | Candidate and window policy | WP-05 |
| WP-07 Actuator extraction | Router and capability results | WP-05 |
| WP-08 Filter repair | Offer facts/gate and UI policy | WP-05 |
| WP-09 Partner profiles | Rapido/Ola configuration | WP-06, WP-07 |
| WP-10 Ola scheduler | Countdown-edge implementation | WP-02, WP-09 |
| WP-11 Adaptive scanner | Queue/tree feedback | WP-02, WP-06 |
| WP-12 Classifier | Scored NLS classification | WP-02, WP-04 |
| WP-13 Release QA | Device matrix and staged rollout | All prior packages |

## 11. Recommended execution order

```text
WP-01 Baseline
   |
WP-02 Metrics
   |-------------------|
WP-03 Safe Cache    WP-04 Signal Handoff
   |-------------------|
WP-05 State Extraction
   |-------------------|
WP-06 Finder       WP-07 Actuators
   |                   |
WP-08 Filters ---- WP-09 Profiles
                       |
                WP-10 Ola Scheduler
                       |
                WP-11 Adaptive Scanner
                       |
                WP-12 Classifier
                       |
                WP-13 Release QA
```

Do not start Ola timing or adaptive polling before metrics exist. Otherwise, improvements cannot be separated from regressions.

## 12. Definition of done

The roadmap is complete when:

1. The current unfiltered Rapido p90 is maintained or improved.
2. Ola unlock-to-attempt timing improves without early taps.
3. No 24-hour unvalidated coordinate can be used.
4. NLS signals survive temporary accessibility disconnection.
5. Every UI filter is either enforced or removed.
6. False-arm and false-tap rates are measured.
7. The engine survives the repeated-ride and OEM test matrix.
8. `AutoClickerService` is reduced to service adaptation and orchestration.
9. Partner rules are versioned and data-driven.
10. Every release can be rolled back by engine feature flag or prior APK.
11. Documentation matches the released constants and behavior.

## 13. Immediate next action

Begin only with **WP-01 and WP-02**:

1. correct stale timing documentation;
2. define the race trace model;
3. add low-overhead timestamps without changing click logic;
4. run the baseline device matrix;
5. use those results to set the real implementation budgets.

The first release should add observability, not new tap behavior. The second release can safely introduce the validated predictive-cache model.
