# Ridio Engine Step-by-Step Implementation Runbook

Date: 2026-09-11  
Status: Implementation sequence; execute one step at a time  
Parent roadmap: `docs/RIDIO_IMPROVEMENT_IMPLEMENTATION_PLAN.md`  
Competitor analysis: `docs/REFERENCE_TAPPER_ANALYSIS.md`

## 1. Purpose

This runbook converts the improvement roadmap into small, testable changes. Each step has a fixed scope, edge cases, tests, rollout gate, and rollback method.

The goal is not “tap everywhere as fast as possible.” The goal is:

> Produce the earliest valid Accept attempt, on the correct ride and window, while keeping the engine responsive for the next ride.

Android, OEM process management, partner-app rendering, network latency, and server-side competition prevent a universal guarantee. Every speed claim must be based on controlled device measurements.

## 2. Non-negotiable safety rules

1. Never tap a default screen fraction.
2. Never use a cached point unless its package, app version, display, orientation, insets, window type, and layout identity match.
3. Never treat `performAction()`, `dispatchGesture()`, input injection, or `PendingIntent.send()` as proof of acceptance.
4. Never block the AccessibilityService main thread waiting for Shizuku, a shell process, disk, React Native, or network I/O.
5. Never scan or log a complete tree before the first valid actuation.
6. Never mix a structural refactor with new timing values in the same step.
7. Never let a callback from an old ride mutate the current ride.
8. Never persist passenger identity, phone number, or route addresses in metrics.
9. Never edit generated `android/` files as the source of truth. Expo prebuild must reproduce all native changes.
10. Never release a timing change without before/after p50, p90, and p99 measurements.
11. Never re-run a stricter classifier after NLS already produced a trusted classification result; pass the result and evidence into the coordinator.
12. Hide Ridio's tap-highlight overlay before privileged injection so Android does not reject the event as obscured.

## 3. Current source facts

These values come from the current native source and are the baseline, even where older documentation says otherwise.

| Current behavior | Source value |
|---|---:|
| State machine | `IDLE → ARMED → STRIKING → VERIFYING → COOLDOWN` |
| Confirmed-success cooldown | 50 ms |
| Cooldown hard recovery cap | 800 ms |
| Verification window | 400 ms |
| Verification poll | 50 ms |
| Maximum verification restrikes | 2 |
| Active Accept hunt poll | 1 ms |
| Armed background poll | 2 ms stock / 1 ms heavy OEM |
| Tree-walk cap | 200 nodes |
| Race arm | 10 seconds |
| Maximum arm from first signal | 18 seconds |
| Rapido predictive cache age | Up to 24 hours |
| Live cached strike age | 12 seconds |
| Predictive accessibility spray | Up to 700 ms / 80 taps |
| Rapido gesture durations | 1 ms short + 16 ms long |
| Ola fallback unlock | First sight/arm + 5 seconds + 160 ms |
| Ola press | 90 ms |
| Ola maximum Shizuku attempts | 2 |
| Stuck STRIKING recovery | 1.8 seconds |
| Stuck VERIFYING recovery | 2.8 seconds |
| Race watchdog | 1 second / 500 ms while hot |

Current Rapido ingress ordering is also a baseline contract:

```text
NLS classification
  → eligible predictive submission
  → notification Accept action
  → postAtFrontOfQueue(main)
  → arm
  → fast live-node hunt
  → verification/follow-ups
```

Known source inconsistencies to correct in Step 1:

- `AutoClickerService` comments still mention a 400 ms cooldown and old poll values.
- `docs/ACCEPT_EDGE_CASES.md` and `docs/DEVICE_OEM_RELIABILITY.md` contain old timing descriptions.
- `src/native/README.md` describes ABI-specific release APKs, while the current plugin disables ABI splits for one universal APK.
- No automated native tests or CI currently exist; the proposed test infrastructure must be created before it can act as a release gate.
- `app.config.js` is authoritative but `app.json` does not contain all of the same Android permissions.
- No explicit Android `versionCode` or native engine version currently identifies field builds.

## 4. Required implementation shape

```text
native-android/
  src/
    AutoClickerService.java
    RideAlertListener.java
    AutoClickerConfig.java
    ...
    engine/
      RaceCoordinator.java
      RaceContext.java
      RaceSignal.java
      RaceState.java
      PendingSignalStore.java
    metrics/
      RaceTrace.java
      RaceMetricsStore.java
      RaceOutcome.java
    cache/
      AcceptPointCache.java
      LayoutFingerprint.java
    finder/
      AcceptCandidate.java
      AcceptFinder.java
      WindowPolicy.java
    actuator/
      ClickRouter.java
      AccessibilityActuator.java
      ShizukuActuator.java
      ActuationResult.java
    filter/
      OfferFacts.java
      OfferGate.java
    profile/
      PartnerProfile.java
      ProfileRegistry.java
      DeviceProfile.java
      OlaUnlockScheduler.java
  test/
    ...matching pure-Java unit tests...
```

The package layout can be introduced gradually. `AutoClickerService` remains the Android lifecycle adapter throughout the migration.

## 5. Change protocol for every step

Perform this checklist for every implementation step:

1. Record the current branch, worktree status, and baseline APK version.
2. Do not include unrelated existing workspace changes.
3. Implement only the named step.
4. Run static checks and unit tests.
5. Regenerate Android with Expo prebuild and verify native source synchronization.
6. Build a release APK.
7. Run the step-specific device scenarios.
8. Compare metrics with the previous accepted baseline.
9. Update the engine version and relevant documentation.
10. Roll out behind its feature flag when the step changes behavior.
11. Stop rollout if any safety or repeated-ride gate fails.
12. Accept the step before beginning the next one.

Each step should be a separate commit or pull request.

---

## Step 0 — Protect the baseline

### Objective

Make the current behavior reproducible before modifying the engine.

### Implementation

1. Create a baseline record containing:
   - Ridio version and commit;
   - Android version;
   - manufacturer/model;
   - RAM class and CPU cores;
   - Rapido/Ola package and version code;
   - Shizuku state;
   - Accessibility/NLS/battery permissions;
   - engine constants listed above.
2. Collect at least 20 eligible ride samples across:
   - first ride after service start;
   - rides 5, 10, and 20;
   - partner app foreground;
   - another app foreground with overlay;
   - screen locked;
   - Ridio removed from Recents.
3. Save only timing and outcome data. Redact notification/tree text.
4. Record failures using stable reason codes, not free-form log interpretation.

### Edge cases

| Edge case | Required handling |
|---|---|
| Existing workspace has unrelated changes | Isolate future engine commits; do not reset user work |
| Different network/account conditions | Do not compare samples as direct speed competitors |
| Partner app updates during baseline | Start a new baseline group |
| Device thermal throttling | Record temperature/thermal status or discard the run |
| Battery saver changes | Record and separate the run |
| No confirmed outcome | Classify `UNKNOWN`, not success or failure |

### Gate

- Baseline dataset exists for Rapido and Ola.
- Every sample has device/app/engine identity.
- No production behavior changed.

---

## Step 1 — Fix documentation and native-source synchronization

### Objective

Ensure Expo prebuild reproduces nested Java sources and tests.

### Implementation

1. Update stale current-value comments and documents.
2. Replace the flat-only source copy in `plugins/withAutoClicker.js` with deterministic recursive copying:
   - source: `native-android/src`;
   - destination: `android/app/src/main/java/com/ridio/app`;
   - preserve subdirectories;
   - copy `.java` files only;
   - sort paths before copying;
   - reject duplicate destination paths;
   - remove stale generated Java files that no longer exist in source.
3. Add equivalent recursive test synchronization:
   - source: `native-android/test`;
   - destination: `android/app/src/test/java/com/ridio/app`.
4. Keep generated `android/` disposable.
5. Add a plugin check that fails prebuild when a source file is skipped.
6. Keep the manifest process rules unchanged:
   - Accessibility, NLS, and keepalive use `:engine`;
   - `stopWithTask=false`;
   - accessibility event flags remain enabled.
7. Add an explicit Android `versionCode` policy and a separate native engine schema/version.
8. Confirm whether `app.json` should be synchronized or reduced to avoid conflicting configuration; keep `app.config.js` authoritative.
9. Verify release minification on a physical device. The plugin enables release shrinking, so reflective Shizuku/hidden-API and React Native registration paths must be tested and given narrowly scoped keep rules if release-only failures appear.

### Best implementation method

Use Expo Continuous Native Generation as designed: source Java and config-plugin logic are authoritative; `npx expo prebuild` generates the Android project. Do not maintain a second hand-edited Java copy in `android/`.

### Edge cases

| Edge case | Required handling |
|---|---|
| Nested directory added | Recursive sync copies it |
| File renamed/deleted | Stale destination is removed |
| Prebuild runs twice | Output is identical |
| Path separator differs | Normalize Windows and POSIX separators |
| Two sources map to one destination | Fail the prebuild |
| Missing source directory | Fail for release builds; warn only when explicitly configured |
| Generated Android has user edits | Document that clean prebuild overwrites them |
| Java package and directory disagree | Build must fail before release |
| Debug succeeds but release minification strips reflection/bridge code | Install and exercise `app-release.apk`; add only required keep rules |
| App and plugin package IDs diverge | Fail prebuild with an explicit package mismatch |

### Tests

1. Run clean prebuild twice.
2. Compare generated Java file lists.
3. Confirm every source and test file exists at the expected destination.
4. Run the Android release build.

### Gate

- Clean prebuild is deterministic.
- Nested package files compile.
- Manifest services still run in `:engine`.
- No tap behavior changed.

---

## Step 2 — Introduce race identity and immutable signals

### Objective

Prevent duplicate and stale asynchronous work from crossing ride boundaries.

### New models

`RaceSignal`:

- `signalId`;
- canonical partner;
- source (`NLS`, `A11Y_NOTIFICATION`, `NLS_REPLAY`, `WINDOW_EVENT`);
- notification key hash when available;
- notification post time;
- receive uptime;
- minimal parsed offer facts;
- classifier reason;
- expiry uptime.

`RaceContext`:

- monotonically increasing `raceGeneration`;
- current signal ID;
- current partner;
- state;
- arm/expiry timestamps;
- current candidate fingerprint;
- filter decision;
- actuation counters;
- verification evidence.

### Implementation

1. Assign a new generation only for a new logical offer.
2. Merge duplicate NLS and accessibility signals into the current race.
3. Capture the generation in every posted runnable and gesture/injection callback.
4. Before a callback mutates state or taps, compare its generation with the current context.
5. Cancel or ignore stale-generation work.
6. Use one serialized coordinator queue for state mutations.
7. Permit read-only capability/cache access from other threads using immutable snapshots.

### Deduplication key

Prefer:

```text
partner + notification key hash + notification post time
```

Fallback:

```text
partner + normalized cue hash + bounded receive-time bucket
```

Do not merge solely by package; two valid back-to-back offers can share a package.

### Edge cases

| Edge case | Required handling |
|---|---|
| NLS and a11y deliver the same notification | Merge; preserve earliest receive time |
| Same notification key is updated | Merge only when it remains the same offer |
| New offer reuses old notification ID | Use post time/cue fingerprint to create a new generation |
| New ride arrives during COOLDOWN | Start a new generation immediately for Rapido |
| New ride arrives during VERIFYING | Old callbacks become stale; new race takes ownership |
| Events arrive out of order | Earliest valid signal wins; stale events cannot reset state |
| Process restarts | Never compare persisted uptime from the old process |
| Wall clock changes | Race logic remains unaffected |
| Notification text is empty | Identity can use key/post time and classifier evidence |

### Gate

- Existing clicks are unchanged.
- Duplicate notification channels produce one race.
- A delayed old callback cannot tap or close a new race.

---

## Step 3 — Add bounded race tracing

### Objective

Measure latency and outcome without slowing the race.

### Implementation

1. Add a fixed-size `RaceTrace` with primitive fields.
2. Timestamp with `SystemClock.uptimeMillis()`.
3. Write hot-path timestamps in memory only.
4. Serialize one compact summary after the outcome or timeout.
5. Store the latest 100 summaries in a bounded local ring buffer.
6. Expose aggregated metrics through the existing native status bridge.
7. Add a “clear diagnostics” operation.
8. Replace existing notification-text log excerpts with structured reason codes, lengths, and non-reversible identifiers before field telemetry is enabled.

### Required timestamps

| Timestamp | Meaning |
|---|---|
| Signal received | First callback entered |
| Signal qualified | Classification completed |
| Predictive attempt | First cache-based actuation submitted |
| Fast find start/end | Fast active-root search |
| Broad find start/end | Multi-window bounded search |
| Accept found | Candidate validated |
| Filter decided | Allow/skip/unknown |
| Node action submitted | `ACTION_CLICK` requested |
| Gesture submitted/callback | Gesture accepted/completed/cancelled |
| Inject submitted/completed | Privileged action lifecycle |
| Accept gone | Verification no longer sees same candidate |
| Terminal outcome | Accepted/missed/filtered/failed/unknown |

### Stable reason codes

At minimum:

```text
SUCCESS_ACCEPT_GONE
SUCCESS_ACCEPTED_UI
MISS_NO_SIGNAL
MISS_NO_CANDIDATE
MISS_CARD_EXPIRED
BLOCK_SELF_UI
BLOCK_BUBBLE
BLOCK_BAD_BOUNDS
BLOCK_STALE_CACHE
BLOCK_FILTER
FAIL_NODE_ACTION
FAIL_GESTURE_CANCELLED
FAIL_SHIZUKU_MISSING
FAIL_SHIZUKU_STOPPED
FAIL_SHIZUKU_DENIED
FAIL_DIRECT_INJECT
FAIL_SHELL
FAIL_VERIFY_UNKNOWN
RECOVER_STUCK_STATE
```

### Edge cases

| Edge case | Required handling |
|---|---|
| Duplicate signals | One trace, multiple source observations |
| More than 100 races | Evict oldest |
| App/process crash | Partial trace may be lost; hot path must not force disk writes |
| Sensitive notification text | Never store it |
| Negative/out-of-order delta | Mark invalid trace rather than publishing latency |
| Trace disabled | No object churn or formatted strings on hot path |
| Clock crosses wall-time change | Monotonic deltas remain valid |
| Unknown verification | Keep separate from accepted and missed |

### Performance gate

- Trace overhead is at most 1 ms p90.
- No extra full-tree walk is performed for metrics.
- Production hot path does not format verbose logs.

---

## Step 4 — Persist and safely replay pending signals

### Objective

Fix `onRideSignal()` dropping the earliest signal when `sInstance == null`.

### Implementation

1. `RideAlertListener` writes a minimal pending signal before calling the service.
2. Prefer an in-process static atomic slot when NLS and accessibility share `:engine`.
3. Add a small SharedPreferences fallback only for process recreation.
4. Store wall-clock post time for persisted expiry; create fresh uptime when consumed.
5. `onServiceConnected()` atomically consumes the latest unexpired signal.
6. Active-notification replay and pending-store replay use the same deduplicator.
7. Clear the pending record after:
   - successful consumption;
   - explicit expiry;
   - notification removal/known miss;
   - auto-accept disabled.

### Do not persist

- `Notification` or `PendingIntent` objects;
- full notification text;
- `AccessibilityNodeInfo`;
- process uptime;
- passenger or route details.

### Edge cases

| Edge case | Required handling |
|---|---|
| NLS fires while accessibility is disconnected | Signal waits for bounded TTL |
| Accessibility reconnects after offer expired | Do not arm |
| Process dies after write before consume | SharedPreferences fallback can recover |
| Active notification replay duplicates pending signal | Deduplicator merges |
| Two offers arrive while disconnected | Keep the newest; count overwritten signal |
| Auto-accept is turned off | Clear pending signal |
| SharedPreferences multi-process cache is stale | Both services share `:engine`; use committed fallback writes |
| Notification action cannot be persisted | Reacquire only from active NLS notification |

### Gate

- `RIDE_SIGNAL_DROP no-service` is eliminated for valid, unexpired signals.
- Reconnect never fires an expired ride.
- Replay does not double-actuate.

---

## Step 5 — Make predictive cache layout-safe

### Objective

Preserve Rapido’s earliest cached strike without using a stale coordinate.

### Cache identity

Store:

- canonical package and actual package;
- package version code;
- Android display ID;
- real width/height;
- orientation;
- density DPI;
- status/navigation/cutout insets;
- app window bounds;
- overlay versus in-app category;
- Accept bounds, center, view ID hash, and normalized label class;
- parent/button geometry fingerprint;
- learned wall time;
- last-confirmed wall time;
- success/failure counters;
- schema version.

### Cache state

```text
EMPTY → OBSERVED → CONFIRMED → SUSPECT → INVALID
```

- `OBSERVED`: live node found, not yet proven.
- `CONFIRMED`: same candidate disappeared with positive acceptance evidence.
- `SUSPECT`: unconfirmed taps or ambiguous disappearance.
- `INVALID`: identity mismatch or safety violation.

Only `CONFIRMED` entries may power an NLS predictive strike.

### Implementation

1. Create a versioned cache schema.
2. Build `LayoutFingerprint` from current display/window and candidate data.
3. Validate all required fields before returning a predictive point.
4. Migrate old `tap_x_*`/`tap_y_*` entries to `OBSERVED`, never directly to `CONFIRMED`.
5. Promote after live candidate verification.
6. Demote/invalidate after repeated ambiguous outcomes.
7. Keep in-app and overlay coordinates separate.
8. Add a user/support cache-reset command.

### Invalidation matrix

| Change/event | Action |
|---|---|
| Partner app version changes | Invalidate |
| Rotation changes | Invalidate |
| Resolution/display ID changes | Invalidate |
| Insets/navigation mode changes | Invalidate |
| Split-screen, freeform, fold posture changes | Invalidate |
| Overlay/in-app category changes | Use separate entry |
| Point enters self window, keyboard, notification shade, or bubble | Invalidate |
| Candidate moves outside tolerance | Replace only after live validation |
| Repeated Accept remains visible | Mark suspect, then invalidate |
| Candidate gone with known missed UI | Invalidate/suspect, not confirm |
| App data restored to another phone | Device/layout mismatch blocks use |

### Predictive-strike gate

All must be true:

1. auto-accept enabled;
2. partner is Rapido;
3. signal classification is high confidence;
4. filters permit fastest mode;
5. cache state is `CONFIRMED`;
6. layout identity matches;
7. point is inside valid display/window bounds;
8. point is outside known unsafe regions;
9. current race generation is active;
10. no terminal outcome or newer ride exists.

### Gate

- Valid cache retains baseline speed.
- Every mismatch returns “no predictive point.”
- No legacy point sprays until revalidated.
- Rotation/app-update/self-UI tests produce zero taps.

---

## Step 6 — Extract the race coordinator without behavior changes

### Objective

Move state transitions and callback ownership out of `AutoClickerService`.

### Implementation order

1. Extract `RaceState`.
2. Extract phase transition validation.
3. Move arm/refresh/expiry bookkeeping.
4. Move cooldown and back-to-back logic.
5. Move verification lifecycle bookkeeping.
6. Move stuck-state watchdog decisions.
7. Keep actual Android calls in the service through interfaces.
8. Compare old/new traces for the same event sequences.

### Legal transitions

```text
IDLE       -> ARMED
ARMED      -> STRIKING | IDLE
STRIKING   -> VERIFYING | ARMED | IDLE
VERIFYING  -> COOLDOWN | ARMED | IDLE
COOLDOWN   -> IDLE | ARMED(new generation)
ANY        -> IDLE(master off/destroy)
ANY        -> ARMED(new valid generation)
```

Invalid transitions must be recorded and ignored or recovered deterministically.

### Edge cases

| Edge case | Required handling |
|---|---|
| STRIKING callback never arrives | Watchdog returns to ARMED/IDLE |
| VERIFYING flag and phase disagree | Coordinator repairs from one source of truth |
| Cooldown timestamp is corrupt/future | Hard-cap recovery clears it |
| New Rapido ride within 50 ms | New generation breaks old cooldown |
| Duplicate current-ride signal | Extend/merge; do not reset actuation counters |
| Master OFF during a race | Cancel all generation work and enter IDLE |
| Service destroy/reconnect | Cancel handlers, recycle nodes, reset transient state |
| Handler runnable executes after cancel | Generation guard makes it a no-op |
| Accept action fires before arm runnable | Same race context owns both |

### Gate

- Golden event-sequence tests match current behavior.
- No timing constant changes.
- Baseline p90 does not regress.

---

## Step 7 — Extract window policy and Accept finder

### Objective

Make target discovery fast, deterministic, and independently testable.

### Search order

1. Validate event source itself.
2. Search known view IDs in the event/root window.
3. Search exact/normalized Accept labels in the root.
4. Search a bounded set of permitted windows.
5. Run bounded BFS only when fast paths miss.
6. Return the best validated `AcceptCandidate`; do not tap inside the finder.

### `AcceptCandidate`

- race generation;
- partner/profile ID;
- node ownership token;
- package evidence;
- label kind (`PLAIN_ACCEPT`, `COUNTDOWN`, `SLIDE`, `UNKNOWN`);
- view ID;
- node/button bounds;
- window bounds and type;
- confidence score;
- candidate fingerprint;
- found uptime;
- discovery path.

### Window policy must reject

- Ridio’s own UI;
- compact bubble/chat-head windows;
- notification shade and system UI unless explicitly supported;
- keyboard/IME;
- zero/negative/off-screen bounds;
- extreme top chrome;
- oversized full-card ancestors;
- wrong partner package;
- stale or detached nodes;
- unlabeled geometry-only targets.

### Edge cases

| Edge case | Required handling |
|---|---|
| Node package is null on Android 15/OEM overlay | Require active race plus independent partner/window evidence |
| Event source is recycled/detached | Catch failure and reacquire from root |
| Clickable parent is too large | Reject using area ratio |
| Accept label is a child of clickable button | Climb bounded parents |
| Multiple Accept labels | Score package/window/visibility/bounds; never choose arbitrarily |
| Bubble is misclassified as a compact card | Require valid Accept evidence and minimum card geometry |
| Bottom sheet over another app | Permit only when partner ownership is established |
| Tree exceeds 200 nodes | Stop bounded scan and record cap hit |
| `getWindows()` returns null/throws | Use safe active-root fallback |
| UI event storm repeats same empty tree | Coalesce misses; never suppress a hit |
| Locale/case/spacing changes | Normalize labels without broad substring false positives |
| Countdown contains “Accept” | Return `COUNTDOWN`; do not route as plain Accept |

### Testing method

Do not build unit tests around live `AccessibilityNodeInfo`. Convert sanitized tree captures into plain fixture nodes, then test pure scoring and policy logic. Keep a thin Android adapter for real nodes.

### Gate

- Existing fixtures select the same valid candidates.
- Bubble/self/wrong-window fixtures return no candidate.
- Candidate selection contains no actuation.

---

## Step 8 — Extract actuators and remove main-thread blocking

### Objective

Provide explicit, observable actuator results without blocking event processing.

### Actuator contract

`submit(request, callback)` returns immediately.

`ActuationResult` distinguishes:

```text
SUBMITTED
TRANSPORT_REJECTED
CALLBACK_COMPLETED
CALLBACK_CANCELLED
DIRECT_INJECT_UNAVAILABLE
DIRECT_INJECT_SENT
SHELL_SENT
SHELL_FAILED
STALE_GENERATION
```

None of these means “ride accepted.”

### Rapido order

Preserve the current earliest-signal ordering until metrics prove a change is better:

1. Submit eligible confirmed-cache predictive channels immediately from the NLS path.
2. Send the notification Accept action when policy allows; do not wait for predictive completion.
3. Post coordinator arm/hunt work at the front of the main queue.
4. Submit live-node `ACTION_CLICK`.
5. Submit one validated accessibility gesture channel.
6. Run controlled live-node restrikes while the same candidate remains visible.

Predictive input, notification action, and live-node work are independent race channels. They share one race generation and verification owner, but must not be serialized behind one another.

### Ola order

1. Validate unlocked live candidate.
2. Submit direct Shizuku injection asynchronously.
3. Use shell press only if direct injection is unavailable or measured unreliable.
4. Retry once only when the same race/candidate remains live.

### Critical correction

Current `ShizukuInput.tapConfirmed()` may wait up to eight seconds for its executor and shell process. It must never be called synchronously from the AccessibilityService handler. Convert the Ola path to asynchronous submission and callback.

### Capability model

Track per session:

- Shizuku missing/stopped/denied/ready;
- direct input manager unknown/ready/unavailable/binder-dead;
- accessibility gesture idle/occupied/rejected;
- node-action observed reliability;
- shell fallback available/failed/timed out.

Reset capability state on binder death, Shizuku reconnect, service reconnect, and Android process restart.

### Edge cases

| Edge case | Required handling |
|---|---|
| Gesture channel already occupied | Queue at most one relevant action or skip; do not pile up |
| Gesture callback arrives for old ride | Generation check discards it |
| Node action and gesture both submit | One coordinator owns retry/verification counters |
| Direct injection reflection breaks | Mark unavailable and use supported fallback |
| Shizuku binder dies mid-race | Fail quickly, refresh capability asynchronously |
| Shell process hangs | Enforce short timeout and destroy process |
| Executor backlog forms | Bounded queue; newer ride cancels stale tasks |
| DOWN succeeds but UP fails | Transport result is ambiguous; verifier decides outcome |
| Shell and direct inject both fire | Only intentional configured Ola fallback; trace separately |
| Screen/display changes after submit | Revalidate generation and layout before execution |
| Accessibility node is recycled | Actuator obtains/owns only documented node lifetime |
| Predictive spray and micro-burst overlap | Preserve the current suppression rule so they do not contend for the gesture/main queue |
| NLS callback is off the main looper | Keep only thread-safe predictive/action submission there; post state/tree work at the front of the main queue |

### Gate

- Accessibility callback thread never waits on Shizuku/shell.
- Every actuator reports a transport result and generation.
- Direct-injection failure has a tested fallback.
- No duplicate uncontrolled action queue.

---

## Step 9 — Centralize filters and define user modes

### Objective

Make every visible filter truthful and deterministic.

### `OfferFacts`

- total fare;
- base fare and bonus when separately parsed;
- pickup distance;
- drop distance;
- units/source;
- confidence per field;
- parse status.

### `OfferGate` decisions

```text
ALLOW
SKIP_BELOW_FARE
SKIP_PICKUP_TOO_FAR
SKIP_DROP_TOO_FAR
WAIT_FOR_REQUIRED_FACTS
ALLOW_UNKNOWN_BY_POLICY
INVALID_CONFIGURATION
```

### User modes

| Mode | Notification action | Unknown required facts |
|---|---|---|
| Fastest | Allowed before full card parsing | Allow with explicit disclosure |
| Strict filters | Wait for card facts | Wait, then skip at deadline |

Do not expose `filterMode` or `maxDrop` until implemented. If not implementing them, remove them from UI/storage instead of leaving inert settings.

### Parsing edge cases

- `₹100`, `Rs. 100`, `INR 100`;
- decimal and comma-formatted fare;
- base + bonus;
- fare range;
- pickup and drop shown in reverse visual order;
- metres versus kilometres;
- “min” must not parse as metres;
- localization and Unicode spacing;
- notification includes only one distance;
- values split across sibling nodes;
- stale facts from the previous ride;
- missing/obscured values;
- offer changes while parsing.

### Gate

- Every exposed setting has unit and fixture tests.
- One gate is used by notification, live-node, predictive, Ola, and retry paths.
- UI explains the speed/strictness trade-off.
- No filtered path can bypass the gate.

---

## Step 10 — Move partner behavior into versioned profiles

### Objective

Isolate Rapido and Ola rules from generic race code.

### Profile contents

- canonical/alias packages;
- supported package-version range;
- labels by locale;
- stable view IDs;
- notification cue weights;
- safe window policy;
- finder order;
- actuator order;
- timing defaults;
- unlock policy;
- retry limits;
- verification evidence;
- cache eligibility.

### Edge cases

| Edge case | Required handling |
|---|---|
| Unknown partner app version | Use conservative profile; disable predictive cache if identity is unproven |
| Package alias | Resolve to canonical partner while retaining actual package |
| Remote profile malformed | Reject and retain signed/bundled known-good profile |
| Profile changes mid-race | Current race retains immutable profile snapshot |
| Label added remotely | Cannot weaken window/bounds safety rules |
| Partner UI experiment | Version/layout override can disable a risky fast path |
| Locale unsupported | Finder may use safe view ID; never use broad geometry fallback |

### Best implementation method

Keep hard safety invariants in code. Profiles may narrow behavior or tune validated timing; they must not enable blind coordinates, remove self-UI guards, or mark transport submission as success.

### Gate

- Existing Rapido/Ola behavior is reproduced by bundled profiles.
- Unknown versions fail conservatively.
- Profile rollback does not require an APK when remote tuning is supported.

---

## Step 11 — Implement Ola countdown-edge scheduling

### Objective

Replace a mostly fixed five-second estimate with an observed unlock-edge model.

### Scheduler inputs

- first live countdown candidate time;
- each countdown value and transition time;
- first plain-Accept time;
- notification receive time;
- candidate fingerprint/bounds;
- current race generation;
- device/app profile observations.

### Algorithm

1. On the first valid Ola countdown candidate, save candidate fingerprint and first-sight time.
2. Observe transitions such as `3 → 2 → 1`.
3. Estimate the next edge from transition intervals.
4. At `ACCEPT IN 1`, schedule a readiness window near the predicted zero edge.
5. In the final window, check every 8–16 ms, selected from measured tree cost.
6. Fire immediately when the same candidate becomes plain Accept.
7. If the label transition is missed, use a conservative measured deadline.
8. Revalidate package, generation, fingerprint, bounds, filter decision, and Shizuku capability immediately before submission.
9. Verify outcome.
10. Retry once only if the identical candidate remains visible and unlocked.

### Preserve initially

Keep the existing first-sight + 5 seconds + grace fallback behind the scheduler. Remove it only after the new path proves equal or better across the matrix.

### Edge cases

| Edge case | Required handling |
|---|---|
| NLS arrives 1–3 seconds before card | Base unlock on live countdown evidence, not NLS alone |
| Countdown starts at 3 or 1 | Build prediction from available observations |
| `ACCEPT IN 1` event is skipped | Plain-Accept transition fires immediately or fallback applies |
| Countdown freezes | Bounded deadline; never loop indefinitely |
| Label disappears | Cancel unless the same candidate is reacquired |
| Card changes to a new ride at same location | Candidate/race fingerprint prevents old tap |
| Plain Accept appears early | Fire after complete live validation |
| Plain Accept is actually another app | Package/window policy rejects |
| Shizuku becomes unavailable | Report capability failure; do not block handler |
| Filter facts arrive late | Follow Fastest/Strict mode policy |
| Device is slow and checks queue late | Trace queue lateness and use next immediate validation |
| Retry callback belongs to old ride | Generation guard cancels |

### Gate

- No early locked-button taps in fixture/device tests.
- Unlock-to-submit p90 improves.
- At most two Ola submissions per race.
- Main thread remains responsive.

---

## Step 12 — Add adaptive scanning

### Objective

Prevent a permanent 1 ms polling loop from slowing low-end phones.

### Implementation

1. Keep event-triggered fast search immediate.
2. Move coalesced broad scans to one high-priority `HandlerThread`.
3. Never pass live `AccessibilityNodeInfo` across threads without defined ownership; prefer requesting a fresh root on the service thread or processing immutable snapshots.
4. Record:
   - last fast/broad scan duration;
   - queue lateness;
   - repeated identical empty results;
   - current race phase;
   - window/candidate proximity;
   - gesture/injection channel state.
5. Select follow-up interval from `2/4/8/16/32 ms`.
6. Tighten only during a live, high-confidence race.
7. Back off immediately when work duration approaches the interval.
8. Stop active polling after terminal outcome/expiry.

### Suggested policy

| Condition | Interval |
|---|---:|
| New high-confidence signal | Immediate |
| Target window visible, no candidate yet | 2–4 ms |
| Countdown near unlock | 8–16 ms readiness checks |
| Repeated empty tree | 8–32 ms |
| Scan duration > current interval | At least next larger interval |
| Standby after success | Existing low-rate policy, then stop |
| No active race | Event-driven only |

### Edge cases

| Edge case | Required handling |
|---|---|
| Event storm | Coalesce pending scan; preserve newest generation |
| Worker dies from exception | Restart and record failure |
| Queue contains old race scans | Generation check drops them |
| Tree scan takes 30 ms | Do not schedule another 1 ms scan |
| Empty tree on heavy OEM | Bounded denser window, then back off |
| Main and worker both find candidate | Coordinator accepts one candidate/action owner |
| Screen locks | Avoid needless tree loops; retain signal handling |
| Low-RAM process pressure | Bounded queues and no unbounded fixture/log allocations |

### Gate

- Signal-to-first-fast-search does not regress.
- Low-end CPU, queue lateness, and battery usage improve.
- No unbounded polling remains.

---

## Step 13 — Harden notification classification

### Objective

Reduce false Ola arms while preserving image-only ride alerts.

### Implementation

1. Replace permissive boolean rules with a scored `RideSignalClassifier`.
2. Record which evidence contributed to the decision.
3. Define outcomes:
   - high confidence: full race and eligible predictive path;
   - medium confidence: arm finder, no predictive coordinate;
   - low confidence: ignore.
4. Learn thresholds from sanitized real/false notification samples.
5. Use package-specific profiles and known channel/notification patterns.

### Positive evidence

- explicit ride-request phrase;
- Accept notification action;
- fare plus pickup evidence;
- known ride channel/category;
- high-priority/full-screen non-ongoing event;
- matching active partner card.

### Negative evidence

- ongoing foreground service;
- online/offline status;
- earnings, incentive, payment, update, chat, or marketing cue;
- duplicate notification update;
- old notification replay outside TTL.

### Edge cases

| Edge case | Required handling |
|---|---|
| Ola image-only high-priority alert | Medium arm until live candidate confirms |
| Rapido random high-priority ping | No predictive strike without strong ride cue |
| Notification has Accept action but status text | Profile/evidence test decides; action alone is not universal |
| NLS replay includes old ride | Post time/TTL blocks |
| Notification text is localized | Profile tokens and action/view evidence |
| Notification is updated after accept | Dedup/terminal race blocks re-arm |
| Notification ID reused | Post time and content fingerprint distinguish |

### Gate

- False-arm rate decreases.
- Real image-only alert recall remains within agreed tolerance.
- Medium-confidence signals can never predictive-tap.

---

## Step 14 — Strengthen verification

### Objective

Separate “action sent” from “ride accepted.”

### Evidence hierarchy

Strong positive:

- accepted/assigned ride UI tied to current partner;
- same Accept candidate disappeared and partner state advanced;
- known post-accept navigation state.

Strong negative:

- missed/taken/expired cue;
- same candidate remains after retry deadline;
- new unrelated card replaces current fingerprint.

Ambiguous:

- root temporarily unavailable;
- app moved background;
- gesture callback completed;
- input injection returned true;
- Accept disappeared while notification shade/self UI obscures partner.

### Implementation

1. Verify the same candidate fingerprint, not merely any “Accept.”
2. Use bounded verification with partner-specific evidence.
3. Classify ambiguity as `UNKNOWN`.
4. Do not write accepted history until positive evidence exists.
5. Permit a controlled retry only while:
   - current generation is unchanged;
   - candidate remains live;
   - filter still allows;
   - retry budget remains.
6. A new ride signal preempts old verification.

### Edge cases

- Accept disappears because tree is temporarily empty;
- another Accept appears for a new offer;
- on-trip chrome already existed before action;
- notification action succeeds with no UI transition;
- partner app backgrounds after action;
- missed UI contains the word “Accept” in history/help;
- multi-window exposes stale and current roots;
- verification callback arrives after service reconnect;
- success history write fails;
- repeated rides occur inside old verification window.

### Gate

- No transport return is counted as acceptance.
- Accepted history contains only positively verified races.
- Unknown and missed are reported separately.

---

## Step 15 — Learn device capabilities from outcomes

### Objective

Augment manufacturer defaults with observed session behavior.

### Inputs

- tree availability and scan duration;
- gesture cancellation rate;
- node-action effectiveness;
- direct-injection availability;
- shell latency/failure;
- Accept-gone latency;
- queue lateness;
- background disconnect/reconnect frequency.

### Rules

1. Start with conservative bundled OEM defaults.
2. Adapt only after a minimum sample count.
3. Bound all learned values.
4. Keep separate profiles by Android and partner-app version.
5. Decay/reset learning after updates or repeated failures.
6. Never learn away safety guards.

### Edge cases

| Edge case | Required handling |
|---|---|
| One lucky/failed ride | Insufficient sample; no profile change |
| App update | Reset layout/timing learning |
| OS update | Reset capability learning |
| Device restore | Device identity mismatch |
| Thermal throttling | Do not permanently learn slow values from one hot session |
| Accessibility permission toggled | Reprobe capabilities |
| Shizuku starts/stops | Capability state updates immediately |

### Gate

- Adaptation improves measured outcomes over static defaults.
- All learned values can be reset.
- No learned profile can enable unsafe coordinates or unlimited retries.

---

## Step 16 — Add diagnostics UI and release controls

### Objective

Make failures explainable and risky behavior independently reversible.

### Feature flags

- race tracing;
- pending-signal replay;
- safe predictive cache;
- extracted coordinator;
- extracted finder;
- asynchronous actuator router;
- strict filters;
- Ola edge scheduler;
- adaptive scanner;
- scored classifier;
- learned device profile.

### Diagnostics should show

- engine/profile version;
- Accessibility/NLS/keepalive state;
- partner package/version;
- current phase and phase age;
- last signal/finder/actuator/outcome reason;
- cache state and invalidation reason, not raw coordinates;
- Shizuku capability state;
- aggregated p50/p90/p99;
- false-arm, false-tap, and unknown counts.

### Edge cases

- UI process and `:engine` process disagree;
- diagnostics read during a state transition;
- old schema after app upgrade;
- remote flag unavailable/offline;
- user disables master switch;
- rollback flag changes mid-race.

Rules:

- Take an immutable health snapshot.
- Apply behavior-changing flags only at the next race boundary, except emergency master-off.
- Bundle safe defaults when remote configuration is unreachable.

### Gate

- Every behavior phase can be disabled independently.
- Support can identify the last failure without collecting sensitive content.

---

## Step 17 — Full device qualification and staged release

### Required devices

| Class | Minimum target |
|---|---|
| Stock Android | Pixel/Android One |
| Samsung | Galaxy A/M One UI |
| Xiaomi | Redmi/POCO MIUI or HyperOS |
| ColorOS family | OPPO/Realme/current OnePlus |
| Vivo family | Vivo/iQOO |
| Transsion | Infinix/Tecno/itel |
| Low RAM | 2–4 GB, four or fewer CPU cores |

### Required scenarios

1. First ride after install.
2. Ride 1, 5, 10, and 20 without reopening Ridio.
3. Rapido/Ola foreground.
4. Another app foreground with ride overlay.
5. Bubble only: zero taps.
6. Ridio UI foreground: zero unsafe taps.
7. Screen locked for five minutes.
8. Ridio swiped from Recents.
9. Accessibility reconnect during active notification.
10. NLS reconnect and active-notification replay.
11. Shizuku ready/stopped/denied/missing.
12. App version change with old cache.
13. Rotation, navigation mode, split-screen, fold/display change.
14. Filters off/Fastest/Strict/unknown facts.
15. Back-to-back offers under 200 ms.
16. Missed/taken/expired card.
17. Event storm and frozen/empty tree.
18. Low battery, battery saver, thermal load, and low-memory pressure.

### Release gates

| Gate | Requirement |
|---|---|
| Safety | Zero known wrong-window/self/bubble taps |
| Rapido speed | Unfiltered p90 no worse than accepted baseline |
| Ola speed | Unlock-to-submit p90 improves without early taps |
| Repeat reliability | Ride 20 remains operational |
| Filter correctness | Zero known policy bypasses |
| Cache correctness | All identity mismatch tests block predictive tap |
| Responsiveness | No Accessibility main-thread wait on shell/injection |
| Stability | No crash, runaway queue, or unbounded poll |
| Observability | Every terminal race has outcome/reason |

### Staged rollout

```text
Internal devices
  → small alpha by known OEM/app versions
  → 5–10% beta
  → 25%
  → 50%
  → general release
```

Pause or rollback when:

- false taps increase;
- Rapido p90 or success regresses;
- Ola early taps appear;
- ride 5/10/20 reliability drops;
- CPU/battery or ANR rate rises;
- filter violations occur;
- service reconnect duplicates actions.

---

## 6. Cross-cutting edge-case register

This register must remain covered as classes are extracted.

| Area | Edge case | Owner |
|---|---|---|
| Signal | NLS before accessibility singleton exists | Pending signal store |
| Signal | NLS and a11y duplicate | Signal deduplicator |
| Signal | Old notification replay | Classifier + TTL |
| Signal | Back-to-back offer reuses notification ID | Race identity |
| State | Old callback acts on new ride | Generation guard |
| State | Stuck STRIKING/VERIFYING/COOLDOWN | Coordinator watchdog |
| State | Master OFF mid-race | Coordinator cancel-all |
| Finder | Null package on OEM overlay | Window policy evidence |
| Finder | Bubble resembles ride card | Window + candidate policy |
| Finder | Multiple Accept nodes | Candidate scoring |
| Finder | Node recycled/detached | Android adapter ownership |
| Finder | Tree exceeds cap | Bounded finder |
| Cache | Partner app updated | Layout fingerprint |
| Cache | Rotation/insets/navigation changed | Layout fingerprint |
| Cache | Point overlaps self UI/bubble/system UI | Unsafe-region gate |
| Cache | Ambiguous Accept disappearance | Cache remains suspect |
| Actuator | Gesture occupied/cancelled | Capability router |
| Actuator | Shizuku binder dies | Capability reset/fallback |
| Actuator | Shell hangs | Async timeout/kill |
| Actuator | Transport reports true but app ignores | Verifier |
| Ola | NLS clock differs from card countdown | Unlock scheduler |
| Ola | Countdown event skipped/frozen | Plain-label check + bounded fallback |
| Ola | Old retry hits new card | Generation + candidate fingerprint |
| Filter | Required value unknown | Explicit mode policy |
| Filter | Pickup/drop confused | Structured parser/fixtures |
| Verify | Tree empty temporarily | Ambiguous, not success |
| Verify | New Accept replaces old | Candidate fingerprint |
| Lifecycle | UI swiped from Recents | `:engine` keepalive |
| Lifecycle | OEM kills whole package | User setup/diagnostic; cannot guarantee recovery |
| Lifecycle | Force stop | Manual restart required |
| Performance | 1 ms loop outruns tree walk | Adaptive scheduler |
| Performance | Event storm fills queue | Coalescing + bounded queue |
| Privacy | Notification/tree content in logs | Structured redacted trace |
| Build | Nested Java omitted by config plugin | Recursive deterministic sync |
| Build | Generated Android diverges | Clean-prebuild verification |

## 7. Test implementation plan

### Pure Java tests

Add tests for:

- signal identity/deduplication;
- legal state transitions;
- generation cancellation;
- arm/cooldown/watchdog;
- cache schema/fingerprint/invalidation;
- filter parsing and decisions;
- partner-profile selection;
- classifier scores;
- Ola countdown prediction;
- retry budgets;
- verification outcome rules;
- adaptive interval selection.

### Fixture tests

Create sanitized plain-data fixtures for:

- Rapido in-app and overlay cards;
- bubble-only state;
- along-route offer;
- missed/taken state;
- Ola countdown 5/4/3/2/1;
- Ola plain Accept;
- Ola expired/accepted state;
- self UI, notification shade, and keyboard;
- null-package OEM overlays;
- multiple candidate windows;
- old and new partner-app layouts.

### Android integration tests

Test:

- service lifecycle;
- config-plugin manifest output;
- NLS/accessibility cross-service handoff;
- SharedPreferences migration;
- gesture callback cancellation;
- Shizuku capability changes;
- health snapshot/React Native bridge.

### Build commands

Use the repository's Expo SDK 57 toolchain:

```powershell
npm run env:check
npx expo prebuild --platform android --clean
cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleRelease
```

With the current universal-APK configuration, the expected release output is:

```text
android/app/build/outputs/apk/release/app-release.apk
```

When Android instrumentation is added and a test device is attached:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The exact Gradle tasks must be confirmed after prebuild because generated variants can change.

## 8. Execution checklist

Implement in this exact order:

- [ ] Step 0 — baseline dataset
- [ ] Step 1 — docs and recursive native sync
- [ ] Step 2 — race identity/generation
- [ ] Step 3 — metrics
- [ ] Step 4 — pending signal replay
- [ ] Step 5 — safe predictive cache
- [ ] Step 6 — coordinator extraction
- [ ] Step 7 — finder/window extraction
- [ ] Step 8 — asynchronous actuator extraction
- [ ] Step 9 — filter repair
- [ ] Step 10 — partner profiles
- [ ] Step 11 — Ola edge scheduler
- [ ] Step 12 — adaptive scanner
- [ ] Step 13 — notification classifier
- [ ] Step 14 — verification hardening
- [ ] Step 15 — learned device capabilities
- [ ] Step 16 — diagnostics and flags
- [ ] Step 17 — device qualification and release

## 9. First implementation slice

The first code change should contain only:

1. stale documentation corrections;
2. recursive native source/test synchronization in `plugins/withAutoClicker.js`;
3. deterministic sync validation;
4. no timing or click behavior changes.

After that slice builds cleanly, implement race identity and tracing. Do not begin predictive-cache, Ola timing, or scanner changes until baseline traces are available.
