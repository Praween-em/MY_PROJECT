package com.ridio.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Race engine (Accessibility) — integrated Accept-first pipeline:
 * Step1: on-trip/nav chrome never blocks Accept hunt.
 * Step2: IDLE→ARMED→STRIKING→VERIFYING→COOLDOWN(400ms)→IDLE + standby hunt.
 * Step3: Captain FG (home+map) 6ms poll + multi-window Accept.
 * Step4: Armed overlay over other apps; bubble refuse; APPLICATION overlays OK.
 * Step5: Exact Accept-center dual-strike (no card-center / blind taps).
 * Step6: NLS spray at last Accept pixel (1ms chained taps) then live hunt.
 * Step7: FOUND_ACCEPT / BLOCKED / PHASE / STRIKE0 / EMIT_RIDE logs.
 */
public class AutoClickerService extends AccessibilityService {

  private static final String TAG = "AutoClickerService";
  /** Extra race diagnostics — OFF in release APKs (hot-path log I/O costs ms). */
  private static final boolean RACE_DEBUG_LOG = false;

  /** Accept-first race phases (Step 2). */
  private enum RacePhase {
    IDLE,
    ARMED,
    STRIKING,
    VERIFYING,
    COOLDOWN
  }

  private static final long COOLDOWN_MS = 3000;
  /**
   * Soft gap between micro-bursts while Accept still visible (retry window).
   * Must NOT be multi-second — a miss must re-strike within tens of ms.
   */
  private static final long RAPIDO_BURST_GAP_MS = 0;
  /** Soft retry window after first strike (ms). */
  private static final long RAPIDO_RETRY_WINDOW_MS = 400;
  /**
   * Tiny pause after a confirmed Accept so the same card is not double-booked.
   * Must stay far below overlay lifetime — next ride can appear in &lt;200ms.
   */
  private static final long RACE_COOLDOWN_MS = 50;
  /** @deprecated Prefer {@link #RACE_COOLDOWN_MS}; kept equal for legacy paths. */
  @Deprecated
  private static final long RAPIDO_POST_HIT_COOLDOWN_MS = RACE_COOLDOWN_MS;
  /**
   * Every Accept press is short then long on all phones.
   * Short wins the server race; long registers on skins that swallow 1ms taps.
   */
  private static final long TAP_MS_SHORT = 1;
  private static final long TAP_MS_LONG = 16;
  /**
   * Default tap duration. Overridden per OEM in {@link #detectAndApplyDeviceProfile()}.
   * ColorOS/MIUI need a real press (~80ms); stock can use shorter taps to win races.
   */
  private static final long RAPIDO_GESTURE_MS_DEFAULT = TAP_MS_LONG;
  /** First-press seed — unused for routing; short+long helper is the real path. */
  private static final long RAPIDO_GESTURE_MS_STOCK = TAP_MS_SHORT;
  private static final long RAPIDO_GESTURE_MS_HEAVY = TAP_MS_LONG;
  /** Micro-burst interval after strike 0 — 0 = next frame (instant). */
  private static final long RAPIDO_MICRO_INTERVAL_MS = 0;
  /** Extra dual strikes after the immediate first click (while verifying). */
  private static final int RAPIDO_MICRO_EXTRA = 5;
  private static final int RAPIDO_MICRO_EXTRA_HEAVY = 5;
  /** Accept-text hunt poll while racing (Captain FG). */
  private static final long ACCEPT_HUNT_POLL_MS = 1;
  /** Armed overlay hunt over other apps — keep near-FG speed (late Accept paint). */
  private static final long ARMED_BG_HUNT_POLL_MS = 2;
  private static final long ARMED_BG_HUNT_POLL_HEAVY_MS = 1;
  /** Skip duplicate empty walks during event storms (misses only). */
  private static final long EMPTY_HUNT_COALESCE_MS = 1;
  /** Keep overlay bounds briefly after a confirmed Accept sighting. */
  private static final long OVERLAY_STICKY_MS = 700;
  /**
   * After NLS/ride ping, arm Accept hunts for BG overlay / late paint.
   * Extended on Accept sighting; capped by {@link #RACE_ARM_MAX_FROM_SIGNAL_MS}.
   */
  private static final long RACE_ARM_MS = 10000;
  /** Refresh arm TTL when Accept is seen (still within max from first signal). */
  private static final long RACE_ARM_EXTEND_MS = 2500;
  /** Hard cap from first arm of this ride signal. */
  private static final long RACE_ARM_MAX_FROM_SIGNAL_MS = 18000;
  /** After strike / PendingIntent: confirm Accept gone before COOLDOWN. */
  private static final long VERIFY_WINDOW_MS = 400;
  private static final long VERIFY_POLL_MS = 50;
  private static final int VERIFY_MAX_RESTRIKES = 2;
  /**
   * Soft Accept-gone watch after VERIFY miss — History UI removed; keep disabled
   * so we never burn 2.5s of tree walks after a miss (stay ARMED via cancelVerify).
   */
  private static final boolean HISTORY_CONFIRM_ENABLED = false;
  private static final long HISTORY_CONFIRM_WINDOW_MS = 2500;
  private static final long HISTORY_CONFIRM_POLL_MS = 120;
  private static final int TREE_WALK_CAP = 200;
  /** Climb to clickable parent — keep short so find→click stays under ~20ms. */
  private static final int PARENT_CLIMB_CLICK = 8;
  /**
   * Reject clickable ancestors larger than this × Accept-text area.
   * Full ride-card parents make center taps miss the Accept button.
   */
  private static final float MAX_CLICK_AREA_RATIO = 4f;
  private static final long SELF_FG_CACHE_MS = 100;
  private static final int FG_NOTIFY_ID = 7142;
  private static final String FG_CHANNEL_ID = "superridex_engine";
  /** Alias — COOLDOWN duration (was 2500ms; blocked along-route offers). */
  private static final long POST_ACCEPT_IGNORE_MS = RACE_COOLDOWN_MS;

  /**
   * Light hot-path labels (findByText is case-insensitive — "Accept" matches ACCEPT).
   * Includes Hindi / Telugu CTA stems used by Captain / regional builds.
   */
  private static final String[] ACCEPT_LABELS_FAST = {
      "Accept",
      "Accept in",
      "ACCEPT IN",
      "Accept Ride",
      "Slide to accept",
      "Accept trip",
      "\u0938\u094d\u0935\u0940\u0915\u093e\u0930", // स्वीकार
      "\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a\u0c41", // అంగీకరించు
      "\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a\u0c41", // స్వీకరించు
  };
  /** Extra labels after FAST — used on all devices. */
  private static final String[] ACCEPT_LABELS_EXTRA = {
      "Ride Accept", "Accept Now", "Accept Karo",
      "Accept Booking", "Accept Order", "Accept Trip", "Take Ride",
      "Tap to Accept", "Tap to accept",
      "Accept in 1", "Accept in 2", "Accept in 3", "Accept in 4", "Accept in 5",
      "ACCEPT IN 1", "ACCEPT IN 2", "ACCEPT IN 3", "ACCEPT IN 4", "ACCEPT IN 5",
      "Slide to Accept", "SLIDE TO ACCEPT", "Swipe to accept",
      "Accept booking", "Confirm booking",
      // Hindi
      "\u0938\u094d\u0935\u0940\u0915\u093e\u0930 \u0915\u0930\u0947\u0902", // स्वीकार करें
      "\u0938\u094d\u0935\u0940\u0915\u093e\u0930\u0947\u0902", // स्वीकारें
      "\u090f\u0915\u094d\u0938\u0947\u092a\u094d\u091f", // एक्सेप्ट
      // Telugu
      "\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a\u0c02\u0c21\u0c3f", // అంగీకరించండి
      "\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a\u0c02\u0c21\u0c3f", // స్వీకరించండి
      "\u0c06\u0c2e\u0c4b\u0c26\u0c3f\u0c02\u0c1a\u0c41", // ఆమోదించు
      // Tamil / Kannada / Marathi / Gujarati / Bengali
      "\u0b8f\u0bb1\u0bcd\u0bb1\u0bc1\u0b95\u0bcd\u0b95\u0bca\u0bb3\u0bcd", // ஏற்றுக்கொள்
      "\u0b8f\u0bb1\u0bcd\u0b95\u0bb5\u0bc1\u0bae\u0bcd", // ஏற்கவும்
      "\u0cb8\u0ccd\u0cb5\u0cc0\u0c95\u0cb0\u0cbf\u0cb8\u0cbf", // ಸ್ವೀಕರಿಸಿ
      "\u0938\u094d\u0935\u0940\u0915\u093e\u0930\u093e", // स्वीकारा
      "\u0ab8\u0acd\u0ab5\u0ac0\u0a95\u0abe\u0ab0\u0acb", // સ્વીકારો
      "\u0997\u09cd\u09b0\u09b9\u09a3", // গ্রহণ
  };

  /**
   * On-trip / navigation chrome — context only.
   * Rapido still shows new Accept cards on map/nav while these are visible.
   * NEVER use these alone to disarm or skip Accept hunting.
   */
  private static final String[] ON_TRIP_CHROME_LABELS = {
      "Trip started", "Trip Started", "End Ride", "Complete Ride",
      "Go to Pickup", "Navigate to pickup", "On Trip", "Navigate",
      "End trip", "Complete trip", "Drop off", "Navigate to dropoff",
  };
  /**
   * Post-accept evidence (Accept button usually gone). Used with Accept-gone for VERIFY only.
   * Do not treat as a hunt block — "Accepted" can appear while another offer still shows Accept.
   */
  private static final String[] JUST_ACCEPTED_LABELS = {
      "Accepted",
  };

  private static final Pattern PRICE_COMBO =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)\\s*\\+\\s*₹\\s*(\\d+(?:\\.\\d+)?)");
  private static final Pattern PRICE_SINGLE =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)");
  /** Pickup / drop distances: "0.8 km", "800 m", "800m". Does not match "min". */
  private static final Pattern DIST_ANY =
      Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(k\\s*m|m)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern PICKUP_DIST =
      Pattern.compile(
          "(?:pick\\s*-?\\s*up|pickup|\u092a\u0940\u0915\u0905\u092a|\u0c2a\u0c3f\u0c15\u0c2a\u0c4d)[^\\d]{0,40}(\\d+(?:\\.\\d+)?)\\s*(k\\s*m|m)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern PRICE_ANY =
      Pattern.compile("(?:₹|rs\\.?|inr|\u0930\u0941)\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
  /** Ola list card: "Accept in 5" / "ACCEPT IN 3" — locked until countdown clears. */
  private static final Pattern ACCEPT_IN_COUNTDOWN =
      Pattern.compile("(?i)accept\\s*in\\s*(\\d+)");
  /**
   * Ola: the 5s slider starts when the Accept card is shown, not when the
   * notification arrives (NLS can be 1–3s earlier). Rapido never waits.
   */
  private static final long OLA_UNLOCK_FROM_ALERT_MS = 5000L;
  /** Fire just after the slider finishes so we do not tap a still-locked CTA. */
  private static final long OLA_UNLOCK_GRACE_MS = 160L;
  /** Keep Ola race armed through unlock + strike. */
  private static final long OLA_ARM_MIN_AFTER_ALERT_MS = 11000L;
  /** One firm Shizuku press after unlock. */
  private static final long OLA_ONE_SHOT_TAP_MS = 90L;
  private static final int OLA_UNLOCK_DEFER_MAX = 16;
  /** First tap + one retry if Accept is still up — not a spray. */
  private static final int OLA_MAX_SHIZUKU_TAPS = 2;

  /** NLS follow-ups after immediate +0 hunt — spans adaptive arm for late overlays. */
  private static final long[] NLS_FOLLOW_DELAYS_MS = {
      1, 2, 4, 6, 8, 12, 16, 22, 30, 42, 60, 90, 140, 220, 400, 700, 1200
  };
  /** Extra late pulses on heavy OEMs where Accept paints after the notif. */
  private static final long[] NLS_FOLLOW_DELAYS_HEAVY_MS = {
      1, 2, 4, 6, 8, 12, 16, 22, 30, 42, 60, 90, 140, 220, 400, 700, 1200, 2400, 4000
  };

  /**
   * Chat-head / float-icon rejection (density-independent dp).
   * Prefer missing a ride over reopening Captain via the float icon.
   * HyperOS / MIUI bubbles often ~72–180dp; some OEMs pad to ~200dp near-square.
   * Wide ride sheets (≥50% / ≥280dp) are exempt from bubble class ONLY when Accept is present
   * (enforced at hunt/click — bounds helper keeps wide sheets out of compact rules).
   */
  private static final int BUBBLE_MAX_DP = 180;
  private static final int BUBBLE_SQUARE_MAX_DP = 200;
  private static final float BUBBLE_ASPECT_MIN = 0.65f;
  private static final float BUBBLE_ASPECT_MAX = 1.4f;
  /** Window area below this fraction of screen → treat as bubble chrome (not ride card). */
  private static final float BUBBLE_AREA_FRAC = 0.12f;
  /** Height cap with area rule — float icon, not a bottom sheet. */
  private static final float BUBBLE_AREA_HEIGHT_FRAC = 0.40f;
  /** Width ≥ this fraction of screen → ride card / bottom sheet candidate. */
  private static final float RIDE_CARD_WIDTH_FRAC = 0.50f;
  /** Absolute width floor (dp) for ride-card exemption. */
  private static final int RIDE_CARD_MIN_WIDTH_DP = 280;

  private static volatile AutoClickerService sInstance;

  private final Handler handler = new Handler(Looper.getMainLooper());
  /** UI process → `:engine`: Auto-accept / nuclear toggles (sInstance is null in UI). */
  private BroadcastReceiver configChangedReceiver;
  private boolean configReceiverRegistered = false;
  /**
   * Sheet/overlay hunt over Home/launcher — briefly unlocks {@link #mayHuntAccept}
   * so Accept can arm without Captain FG / NLS text match. Same on every phone.
   */
  private volatile boolean allowSheetHunt = false;
  private volatile long lastOverlayProbeMs = 0L;
  /** Ride signal that arrived during COOLDOWN — replay after latch clears (back-to-back). */
  private volatile boolean pendingRideSignal = false;
  private volatile String pendingRidePkg = null;
  private volatile String pendingRideText = null;
  private volatile String pendingRideSource = null;
  private volatile long pendingRideAtMs = 0L;
  private Notification pendingRideNotification = null;

  // ── Reused scratch objects (a11y/main thread only — never cross-thread) ──
  private final Rect scratchRect = new Rect();
  private final Rect scratchRect2 = new Rect();
  private final Path scratchPath = new Path();
  private final StringBuilder scratchSb = new StringBuilder(512);
  private final ArrayDeque<AccessibilityNodeInfo> scratchQueue = new ArrayDeque<>(64);
  /** Persistent overlay bounds — mutated via set(), never replaced on hot path. */
  private final Rect overlayCardBoundsRect = new Rect();
  /** Last seen Rapido float-icon window (dp-rejected) — hot-path gesture gate. */
  private final Rect lastBubbleWindowRect = new Rect();
  private volatile boolean lastBubbleWindowValid = false;
  private volatile long lastBubbleSeenAtMs = 0;
  private static final long BUBBLE_STICKY_MS = 4_000;

  /** Last micro-burst start — used with RAPIDO_BURST_GAP_MS inside retry window. */
  private volatile long lastRapidoAttemptMs = 0;
  /** Soft window: re-burst while Accept still visible (short gap only). */
  private volatile long rapidoRetryUntilMs = 0;
  /** Hard rest after retry window / burst success — not applied on a single miss. */
  private volatile long rapidoCooldownUntilMs = 0;
  private volatile long lastHuntAtMs = 0;
  private volatile long lastEmitAtMs = 0;
  private volatile boolean rapidoBurstLock = false;
  private volatile boolean acceptHuntPollScheduled = false;
  private volatile boolean rapidoForeground = false;
  /** Ride alert floating as SYSTEM_ALERT_WINDOW / overlay card (may show over Home). */
  private volatile boolean rideOverlayActive = false;
  private volatile long raceArmedUntilMs = 0;
  /** Wall-clock of first arm for this ride signal (caps refreshRaceArm). */
  private volatile long raceArmedFromMs = 0;
  private volatile int overlayTapX = 0;
  private volatile int overlayTapY = 0;
  private volatile boolean overlayCardBoundsValid = false;
  /** Last driver app from NLS / a11y. Empty until a real Ola / Rapido signal. */
  private volatile String lastPkg = "";
  private volatile long overlayStickyUntilMs = 0;
  /** Live Rapido overlay confirmed by findBest — sticky alone must NOT unlock gestures. */
  private volatile long overlayLiveUntilMs = 0;
  /** True after we confirm an Accept hit until next NLS — blocks residual hunts briefly. */
  private volatile boolean acceptSuccessLatch = false;
  /** Hard wall-clock latch after Accept hit (survives NLS clear attempts). */
  private volatile long ignoreRapidoUntilMs = 0;
  /** Captain shows nav / End Ride chrome — still hunt Accept (along-route offers). */
  private volatile boolean driverOnTripChrome = false;
  /** Explicit Accept-first race phase (IDLE→ARMED→STRIKING→VERIFYING→COOLDOWN). */
  private volatile RacePhase racePhase = RacePhase.IDLE;
  /** When current racePhase was entered — detect stuck STRIKING/VERIFYING. */
  private volatile long racePhaseEnteredAtMs = 0;
  /** STRIKING without progress longer than this → force recover. */
  private static final long STUCK_STRIKING_MS = 1800;
  /** VERIFYING longer than this → abandon and allow next ride. */
  private static final long STUCK_VERIFYING_MS = 2800;
  /** Periodic unlock even when OEM sends zero a11y events. */
  private static final long RACE_WATCHDOG_MS = 1000;
  /** Faster heartbeat while racing / between back-to-back offers. */
  private static final long RACE_WATCHDOG_HOT_MS = 500;
  /**
   * After a confirmed Accept, keep a label-only hunt alive so ride 4+ / along-route
   * cards are found even when NLS is quiet and Maps is foreground.
   */
  private static final long STANDBY_HUNT_MS = 180_000;
  /** Poll while standby / Ola FG idle — label-only, not the 1ms race spray. */
  private static final long STANDBY_HUNT_POLL_MS = 24;
  /** Never remain in COOLDOWN longer than this (400ms is the real window). */
  private static final long COOLDOWN_HARD_CAP_MS = 800;
  /** Empty hunts this long while we expect Accept → refresh a11y windows. */
  private static final long FROZEN_TREE_EMPTY_MS = 2500;
  private static final long FROZEN_TREE_REFRESH_COOLDOWN_MS = 8000;
  /**
   * After overlay/notification hunt misses, open Captain via contentIntent once —
   * same as user opening orders page (OEMs that hide Accept from a11y overlay).
   */
  private static final long CONTENT_INTENT_FALLBACK_MS = 180L;

  private Notification pendingContentIntentNotif = null;
  private volatile boolean contentIntentSentThisSignal = false;
  private volatile boolean acceptFoundThisSignal = false;
  private volatile String contentIntentSignalPkg = null;
  private volatile long contentIntentSignalT0 = 0L;

  /** VERIFYING: strike/PendingIntent fired — wait for Accept gone before COOLDOWN. */
  private volatile boolean verifyingAccept = false;
  private volatile long verifyUntilMs = 0;
  private volatile int verifyRestrikeCount = 0;
  private volatile int verifyCx = 0;
  private volatile int verifyCy = 0;
  private volatile String verifyPkg = null;
  private volatile String verifyTag = null;
  private volatile long verifyFindAt = 0;
  /** True when verify started from notif Accept action (may succeed with no UI Accept). */
  private volatile boolean verifyFromPendingIntent = false;
  /** History emit once per confirmed Accept (after VERIFY) — never mid-race. */
  private volatile boolean raceEmitted = false;
  /** Stash strike metrics; flush only from disarmAfterAcceptSuccess (async). */
  private volatile boolean pendingHistoryValid = false;
  private volatile String pendingHistoryPkg = null;
  private volatile int pendingHistoryMs = 0;
  private volatile int pendingHistoryFare = 0;
  /** True only after Accept was visible post-strike — blocks fake history. */
  private volatile boolean acceptSeenDuringVerify = false;
  private volatile long historyConfirmUntilMs = 0;
  /** Last fare (₹) seen for the current race — used in onRideAccepted history. */
  private volatile int lastRideFare = 0;
  /** Last pickup distance (km) for current race — 0 = unknown. */
  private volatile float lastRidePickupKm = 0f;
  /** Ola: first Accept sight this race (warm coords while waiting alert+5s). */
  private volatile long olaAcceptFirstSeenMs = 0L;
  /** Ola: saw "Accept in N" (log only — unlock clock is alert arm time). */
  private volatile boolean olaSawCountdownThisRace = false;
  /** Ola: exact unlock strike posted for alert+5s. */
  private volatile boolean olaUnlockStrikeScheduled = false;
  /** Ola: Shizuku taps sent this race (cap {@link #OLA_MAX_SHIZUKU_TAPS}). */
  private volatile int olaShizukuTapsThisRace = 0;
  /** Ola: unlock fire deferred because countdown / lock still showing. */
  private volatile int olaUnlockDeferCount = 0;
  /** Absolute uptime when the Ola unlock runnable should fire. */
  private volatile long olaUnlockFireAtMs = 0L;
  /** Throttle SKIP_DISABLED logs when Auto-accept is OFF. */
  private volatile long lastSkipDisabledLogUptime = 0L;
  /** True after haltEngineMasterOff until Auto-accept is turned back ON. */
  private volatile boolean engineHaltedForMasterOff = false;
  /** Keep hunting this long after ACCEPT_OK (along-route / next ping). */
  private volatile long standbyHuntUntilMs = 0;
  private volatile long lastAcceptOkAtMs = 0;
  /** Consecutive empty hunts — OEM frozen tree detector. */
  private volatile int consecutiveEmptyHunts = 0;
  private volatile long emptyHuntStreakStartMs = 0;
  private volatile long lastFrozenTreeRefreshMs = 0;
  private volatile int consecutiveGestureFails = 0;
  /** Throttle OLA_LOCKED logs. */
  private volatile long lastOlaLockedLogMs = 0L;

  // Screen metrics cache (refresh rarely) — volatile for NLS-thread predictive tap
  private volatile int screenW;
  private volatile int screenH;
  private float screenArea;
  private float density = 3f;
  private long screenMetricsAtMs = 0;
  private static final long SCREEN_METRICS_TTL_MS = 60_000;

  // NLS follow-up coalesce — one chain, cancel/restart on new ping
  private volatile boolean nlsFollowActive = false;
  private String nlsFollowPkg;
  private long nlsFollowT0;
  private int nlsFollowIndex;

  // Rapido micro-burst state (main-thread only for node ref)
  private AccessibilityNodeInfo rapidoBurstNode;
  private int rapidoBurstX;
  private int rapidoBurstY;
  private String rapidoBurstPkg;
  private String rapidoBurstTag;
  private long rapidoBurstT0;
  private int rapidoBurstIndex;
  private boolean rapidoBurstFirstOk;

  /** Cached Accept center from a real Rapido Accept node (gesture gate). */
  private volatile int cachedAcceptX = 0;
  private volatile int cachedAcceptY = 0;
  private volatile boolean cachedAcceptValid = false;
  private volatile String cachedAcceptPkg = null;
  /** Uptime when cache was last refreshed from a live Accept node. */
  private volatile long cachedAcceptAtMs = 0;
  /** CACHE_STRIKE only valid briefly after a real sighting. */
  private static final long CACHED_ACCEPT_STRIKE_MAX_AGE_MS = 12_000;
  /**
   * NLS spray may reuse last Accept pixel for hours — the CTA sits in the
   * same place. Live-node CACHE_STRIKE stays at 12s.
   */
  private static final long PREDICTIVE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L;
  /** 1ms taps chained via gesture callback — Android rejects overlapping gestures. */
  private static final long ALERT_SPRAY_TAP_MS = 1L;
  /** Keep spraying while ColorOS paints Accept (late overlay). */
  private static final long ALERT_SPRAY_WINDOW_MS = 700L;
  private static final int ALERT_SPRAY_MAX_TAPS = 80;
  private volatile int predictivePulseX = 0;
  private volatile int predictivePulseY = 0;
  private volatile int predictivePulseLeft = 0;
  private volatile long lastPredictiveTapMs = 0;
  private volatile boolean alertSprayActive = false;
  private volatile int alertSprayLeft = 0;
  private volatile int alertSprayX = 0;
  private volatile int alertSprayY = 0;
  private volatile long alertSprayUntilMs = 0;
  private volatile int alertSprayFails = 0;
  /** Same Accept pixel again while the card is still painting. */
  private final Runnable predictivePulseRunnable = new Runnable() {
    @Override
    public void run() {
      continueAlertSpray("pulse");
    }
  };
  private final GestureResultCallback alertSprayCb = new GestureResultCallback() {
    @Override
    public void onCompleted(GestureDescription gestureDescription) {
      continueAlertSpray("ok");
    }

    @Override
    public void onCancelled(GestureDescription gestureDescription) {
      continueAlertSpray("cancel");
    }
  };

  // ── Performance / OEM profile ──
  private boolean lowEndDevice = false; // FG notify hint only
  private boolean heavyOem = false; // ColorOS / MIUI / Vivo — longer press, denser follow-ups
  private int treeWalkCap = TREE_WALK_CAP;
  private long acceptHuntPollMs = ACCEPT_HUNT_POLL_MS;
  private long armedBgHuntPollMs = ARMED_BG_HUNT_POLL_MS;
  private long rapidoGestureMs = RAPIDO_GESTURE_MS_DEFAULT;
  private int rapidoMicroExtraStrikes = RAPIDO_MICRO_EXTRA;
  private long rapidoMicroIntervalMs = RAPIDO_MICRO_INTERVAL_MS;
  private long[] nlsFollowDelays = NLS_FOLLOW_DELAYS_MS;
  private long raceArmMs = RACE_ARM_MS;
  private boolean foregroundStarted = false;
  private volatile long selfFgCachedAtMs = 0;
  private volatile boolean selfFgCached = false;
  /** Last empty Accept hunt (coalesce duplicate misses only — never suppress hits). */
  private volatile long lastEmptyHuntAtMs = 0;
  /** Throttle HUNT_EMPTY / OVERLAY_PROBE miss logs. */
  private volatile long lastEmptyHuntLogMs = 0;
  /** Throttle expensive missed-order tree dumps (not on every strike). */
  private volatile long lastMissedScanAtMs = 0;
  private volatile boolean lastMissedScanHit = false;
  private static final long MISSED_SCAN_THROTTLE_MS = 450;

  /**
   * Accept-hunt poll — FG ~6ms; armed BG ~20ms. findAccept → smartClick.
   */
  private final Runnable acceptHuntPollRunnable = new Runnable() {
    @Override
    public void run() {
      acceptHuntPollScheduled = false;
      if (!shouldRunAcceptHuntPoll()) {
        return;
      }
      recoverIfRaceStuck("hunt-poll");
      long t0 = SystemClock.uptimeMillis();
      // While verifying a UI strike, burst owns restrikes — skip parallel hunt
      if (!verifyingAccept || verifyFromPendingIntent || !rapidoBurstLock) {
        // Only FG / armed poll — never idle sheet spam (caused mid-screen taps)
        huntAccept(lastPkg, t0,
            rapidoForeground ? "FgAcceptPoll" : "ArmedAcceptPoll");
      }
      scheduleAcceptHuntPoll();
    }
  };

  /**
   * Heartbeat: expire COOLDOWN latch + recover stuck STRIKE/VERIFY even when
   * FunTouch/ColorOS/MIUI stop delivering a11y events after 2–3 rides.
   * Never unscheduled except onDestroy — a halt must not kill ride 4+.
   */
  private final Runnable raceWatchdogRunnable = new Runnable() {
    @Override
    public void run() {
      try {
        if (!engineIsOn()) {
          return;
        }
        if (engineHaltedForMasterOff) {
          resumeEngineMasterOn("watchdog-enabled");
        }
        // Expire ignore latch / COOLDOWN → IDLE + resume hunt
        isRapidoInteractionBlocked();
        recoverIfRaceStuck("watchdog");
        // Safety: sticky ignore longer than 2s must not kill ride 3+
        long now = SystemClock.uptimeMillis();
        if (ignoreRapidoUntilMs > 0 && now > ignoreRapidoUntilMs + 50L) {
          ignoreRapidoUntilMs = 0;
          acceptSuccessLatch = false;
        }
        if (ignoreRapidoUntilMs > now + 2_500L) {
          Log.w(TAG, "WATCHDOG_CLEAR_STICKY_IGNORE remaining="
              + (ignoreRapidoUntilMs - now));
          ignoreRapidoUntilMs = 0;
          acceptSuccessLatch = false;
          rapidoCooldownUntilMs = 0;
        }
        maybeRefreshFrozenTree("watchdog");
        flushPendingRideSignal("watchdog");
        // Captain FG / armed / standby — keep hunt alive for continuous accepts
        if (shouldRunAcceptHuntPoll()) {
          scheduleAcceptHuntPoll();
        } else if (!isRapidoInteractionBlocked() && !shouldIdleForBubbleOnly()) {
          sheetOverlayProbe(now);
        }
      } finally {
        long delay = RACE_WATCHDOG_MS;
        if (engineIsOn() && (isRaceActive() || isStandbyHunting() || rapidoForeground)) {
          delay = RACE_WATCHDOG_HOT_MS;
        }
        handler.postDelayed(this, delay);
      }
    }
  };

  /** Extra dual strikes while VERIFYING (Accept still on screen). Always gesture. */
  private final Runnable rapidoMicroBurstRunnable = new Runnable() {
    @Override
    public void run() {
      if (!engineIsOn() || isRapidoInteractionBlocked()) {
        finishRapidoMicroBurst("abort");
        return;
      }
      if (isSelfAppForeground() && !hasLiveRapidoOverlayCard()) {
        clearStaleAcceptTapPoints("micro-self");
        finishRapidoMicroBurst("self-ui");
        return;
      }
      // Float icon: never keep striking bubble coords / bubble nodes
      if (rapidoBurstX > 0 && rapidoBurstY > 0 && pointInsideRapidoBubbleWindow(rapidoBurstX, rapidoBurstY)) {
        clearCachedAcceptPoint("bubble-point");
        finishRapidoMicroBurst("bubble");
        return;
      }
      if (rapidoBurstNode != null && isFloatingBubbleNode(rapidoBurstNode)) {
        clearCachedAcceptPoint("bubble-node");
        finishRapidoMicroBurst("bubble");
        return;
      }
      // Re-find live Accept each strike — never reuse stale mid-screen coords
      AccessibilityNodeInfo live = findAcceptNodeAnywhere();
      if (live == null) {
        clearStaleAcceptTapPoints("micro-no-accept");
        finishRapidoMicroBurst("no-accept");
        return;
      }
      AccessibilityNodeInfo target = null;
      try {
        live.getBoundsInScreen(scratchRect);
        if (scratchRect.isEmpty()
            || isExtremeTopChromeAccept(scratchRect)
            || isOversizedAcceptBounds(scratchRect)) {
          clearStaleAcceptTapPoints("micro-bad-bounds");
          finishRapidoMicroBurst("bad-bounds");
          return;
        }
        if (isBubbleLikeClickTarget(scratchRect) && !isAcceptRaceHot()) {
          clearStaleAcceptTapPoints("micro-bubble");
          finishRapidoMicroBurst("bad-bounds");
          return;
        }
        final int cx = scratchRect.centerX();
        final int cy = scratchRect.centerY();
        if (cx <= 0 || cy <= 0) {
          finishRapidoMicroBurst("bad-center");
          return;
        }
        target = resolveAcceptClickTarget(live);
        if (target == null) target = AccessibilityNodeInfo.obtain(live);
        rapidoBurstX = cx;
        rapidoBurstY = cy;
        verifyCx = cx;
        verifyCy = cy;
        // Sticky = Accept LABEL bounds only (never full-card → mid-screen)
        scratchRect2.set(scratchRect);
        markOverlayLive(scratchRect2, cx, cy);
        if (!rapidoForeground) setRideOverlayActive(true, "micro-burst");
        // Rapido: raw ACTION_CLICK only — skip gesture gate (was adding latency)
        String burstPkg = rapidoBurstPkg != null ? rapidoBurstPkg : lastPkg;
        if (AutoClickerConfig.isCaptainRapidoPackage(burstPkg)) {
          boolean ok = false;
          try {
            if (target.isClickable()) {
              ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            if (!ok) {
              ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
          } catch (Exception ignored) {
          }
          if (!ok && cx > 0 && cy > 0) {
            dispatchShortAndLongTap(cx, cy);
          }
        } else if (AutoClickerConfig.isOlaPackage(burstPkg)) {
          // Ola: one Shizuku tap at unlock only — never micro-burst (looks automated)
          finishRapidoMicroBurst("ola-no-burst");
          return;
        } else {
          if (!canGestureAt(cx, cy)) {
            finishRapidoMicroBurst("gate");
            return;
          }
          dualStrikeAccept(target, cx, cy, true);
        }
      } finally {
        try { live.recycle(); } catch (Exception ignored) {}
        if (target != null) {
          try { target.recycle(); } catch (Exception ignored) {}
        }
      }
      rapidoBurstIndex++;
      if (rapidoBurstIndex < rapidoMicroExtraStrikes && verifyingAccept) {
        if (rapidoMicroIntervalMs <= 0L) {
          handler.postAtFrontOfQueue(this);
        } else {
          handler.postDelayed(this, rapidoMicroIntervalMs);
        }
      } else {
        finishRapidoMicroBurst("done");
      }
    }
  };

  /**
   * Post-VERIFY soft watcher (History UI removed — {@link #HISTORY_CONFIRM_ENABLED}=false).
   * Left in place so a future toggle can re-enable without rewriting VERIFY.
   */
  private final Runnable historyConfirmRunnable = new Runnable() {
    @Override
    public void run() {
      if (!HISTORY_CONFIRM_ENABLED) {
        clearPendingAcceptHistory();
        return;
      }
      if (raceEmitted || !pendingHistoryValid) return;
      if (!engineIsOn()) {
        clearPendingAcceptHistory();
        return;
      }
      long now = SystemClock.uptimeMillis();
      boolean acceptVisible = true;
      try {
        acceptVisible = acceptStillVisibleAnywhere();
      } catch (Exception ignored) {
      }
      // ONLY confirm when Accept was seen after our strike, then disappeared.
      if (acceptSeenDuringVerify && !acceptVisible) {
        disarmAfterAcceptSuccess("hist-accept-gone");
        return;
      }
      if (acceptVisible) {
        acceptSeenDuringVerify = true;
      }
      if (now < historyConfirmUntilMs) {
        handler.postDelayed(this, HISTORY_CONFIRM_POLL_MS);
      } else {
        clearPendingAcceptHistory();
        Log.i(TAG, "HISTORY_CONFIRM_TIMEOUT");
      }
    }
  };

  private void scheduleHistoryConfirmWatch(String reason) {
    // History tab removed — do not schedule tree-walk soft-confirm (CPU after miss).
    if (!HISTORY_CONFIRM_ENABLED) return;
    if (!pendingHistoryValid || raceEmitted) return;
    historyConfirmUntilMs = SystemClock.uptimeMillis() + HISTORY_CONFIRM_WINDOW_MS;
    handler.removeCallbacks(historyConfirmRunnable);
    handler.postDelayed(historyConfirmRunnable, HISTORY_CONFIRM_POLL_MS);
    Log.i(TAG, "HISTORY_CONFIRM_WATCH " + reason);
  }

  /**
   * VERIFYING poll: disarm only when Accept is gone or on-trip UI appears.
   * PendingIntent alone never soft-assumes success — keep hunting the overlay Accept.
   * If Accept stays visible, re-strike until budget expires — then stay armed (miss).
   */
  private final Runnable verifyAcceptRunnable = new Runnable() {
    @Override
    public void run() {
      if (!verifyingAccept) return;
      if (!engineIsOn()) {
        cancelVerify("abort");
        return;
      }
      if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) {
        verifyingAccept = false;
        return;
      }

      long now = SystemClock.uptimeMillis();
      boolean onTrip = rapidoLooksOnTripAnywhere();
      boolean acceptVisible = acceptStillVisibleAnywhere();
      if (onTrip) driverOnTripChrome = true;

      // Dead Missed-order card still shows Accept — never count as a win
      if (rootLooksLikeMissedOfferAnywhere()) {
        Log.i(TAG, "VERIFY_MISS missed-order-ui");
        cancelVerify("verify-missed-order");
        finishRapidoMicroBurst("verify-missed-order");
        clearPendingAcceptHistory();
        return;
      }

      // PendingIntent path FIRST — send() is not proof of accept.
      // Do NOT treat "Accept not in tree yet" as a win (that aborted all hunting).
      if (verifyFromPendingIntent) {
        if (acceptVisible) {
          acceptSeenDuringVerify = true;
          verifyFromPendingIntent = false; // fall through to UI restrike path
        } else if (now >= verifyUntilMs) {
          cancelVerify("pending-no-ui");
          finishRapidoMicroBurst("pending-no-ui");
          // No UI Accept → no history (PendingIntent alone is not a confirmed tap)
          clearPendingAcceptHistory();
          tryContentIntentFallback("pending-no-ui");
          scheduleAcceptHuntPoll();
          return;
        } else {
          handler.postDelayed(this, VERIFY_POLL_MS);
          return;
        }
      }

      // Accept gone → confirm ONLY if we struck a live Accept this race
      if (!acceptVisible) {
        clearStaleAcceptTapPoints("verify-accept-gone");
        if (pendingHistoryValid && acceptSeenDuringVerify) {
          disarmAfterAcceptSuccess(onTrip ? "verify-on-trip-gone" : "verify-gone");
        } else {
          // No real Accept tap this race — do not write history / do not latch
          cancelVerify("verify-gone-no-strike");
          finishRapidoMicroBurst("verify-gone-no-strike");
          clearPendingAcceptHistory();
        }
        return;
      }
      // Accept still on screen after our strike
      acceptSeenDuringVerify = true;

      // Ola: one unlock tap only — VERIFY restrikes look like a bot spray
      if (AutoClickerConfig.isOlaPackage(verifyPkg != null ? verifyPkg : lastPkg)) {
        if (now < verifyUntilMs) {
          handler.postDelayed(this, VERIFY_POLL_MS);
        } else {
          Log.i(TAG, "VERIFY_MISS ola-oneshot acceptStillVisible=true");
          cancelVerify("verify-miss-ola");
          finishRapidoMicroBurst("verify-miss-ola");
        }
        return;
      }

      // Accept still visible (even during on-trip chrome) — re-strike
      if (now < verifyUntilMs && verifyRestrikeCount < VERIFY_MAX_RESTRIKES) {
        refreshRaceArm("verify-restrick");
        verifyRestrikeCount++;
        if (verifyFromPendingIntent) {
          verifyFromPendingIntent = false;
        }
        AccessibilityNodeInfo hit = findAcceptNodeAnywhere();
        if (hit != null) {
          AccessibilityNodeInfo target = null;
          try {
            // Always gesture at Accept LABEL center — parent center is mid-card spam
            hit.getBoundsInScreen(scratchRect);
            int cx = scratchRect.centerX();
            int cy = scratchRect.centerY();
            target = resolveAcceptClickTarget(hit);
            if (target == null) target = AccessibilityNodeInfo.obtain(hit);
            if (cx > 0 && cy > 0 && !isBubbleLikeClickTarget(scratchRect)
                && !isOversizedAcceptBounds(scratchRect)) {
              verifyCx = cx;
              verifyCy = cy;
              cacheAcceptPoint(verifyPkg != null ? verifyPkg : lastPkg, cx, cy);
              scratchRect2.set(scratchRect);
              markOverlayLive(scratchRect2, cx, cy);
              if (!rapidoForeground) setRideOverlayActive(true, "verify-restrick");
              Log.i(TAG, "VERIFY_RESTRIKE @" + cx + "," + cy + " n=" + verifyRestrikeCount
                  + " onTripChrome=" + driverOnTripChrome);
              dualStrikeAccept(target, cx, cy, true);
            } else {
              clearStaleAcceptTapPoints("verify-bad-bounds");
            }
          } finally {
            try { hit.recycle(); } catch (Exception ignored) {}
            if (target != null) {
              try { target.recycle(); } catch (Exception ignored) {}
            }
          }
        }
        // No blind VERIFY_GESTURE — mid-screen spam when Accept node is gone
        handler.postDelayed(this, VERIFY_POLL_MS);
        return;
      }

      // Timeout with Accept still up — stop restrikes; stay ARMED (no history soft-watch)
      Log.i(TAG, "VERIFY_MISS stay-armed acceptStillVisible=true onTripChrome="
          + driverOnTripChrome);
      cancelVerify("verify-miss");
      finishRapidoMicroBurst("verify-miss");
      clearStaleAcceptTapPoints("verify-miss");
      scheduleHistoryConfirmWatch("verify-miss"); // no-op when HISTORY_CONFIRM_ENABLED=false
    }
  };

  /** When soft retry window ends without a new burst, apply post-hit cooldown only on verified hit. */
  private final Runnable rapidoRetryWindowEndRunnable = new Runnable() {
    @Override
    public void run() {
      long now = SystemClock.uptimeMillis();
      if (rapidoBurstLock || verifyingAccept) {
        // Burst / verify still running — re-check after it finishes
        handler.postDelayed(this, rapidoMicroIntervalMs * 2);
        return;
      }
      // Only after verified Accept latch — never on optimistic click boolean
      if (!acceptSuccessLatch) return;
      if (now >= rapidoRetryUntilMs && now >= rapidoCooldownUntilMs) {
        rapidoCooldownUntilMs = now + RAPIDO_POST_HIT_COOLDOWN_MS;
      }
    }
  };

  /** Coalesced NLS follow-up chain — single Runnable, no per-delay lambda alloc. */
  private final Runnable nlsFollowRunnable = new Runnable() {
    @Override
    public void run() {
      if (!engineIsOn()) {
        nlsFollowActive = false;
        return;
      }
      if (isRapidoInteractionBlocked()) {
        // Cooldown after accept — keep the follow-up chain for ride 2+
        handler.postDelayed(this, 80);
        return;
      }
      if (shouldIdleForBubbleOnly()) {
        nlsFollowActive = false;
        return;
      }
      String pkg = nlsFollowPkg != null ? nlsFollowPkg : lastPkg;
      long t0 = nlsFollowT0;
      huntAccept(pkg, t0, "NlsHunt");
      nlsFollowIndex++;
      if (nlsFollowIndex < nlsFollowDelays.length) {
        long delta = nlsFollowDelays[nlsFollowIndex]
            - nlsFollowDelays[nlsFollowIndex - 1];
        handler.postDelayed(this, Math.max(1, delta));
      } else {
        nlsFollowActive = false;
      }
    }
  };

  /** Drop BG arm leftovers when the NLS window expires without a new offer. */
  private final Runnable raceArmExpireRunnable = new Runnable() {
    @Override
    public void run() {
      if (isRaceArmed()) return;
      raceArmedFromMs = 0;
      handler.removeCallbacks(nlsFollowRunnable);
      handler.removeCallbacks(olaUnlockStrikeRunnable);
      olaUnlockStrikeScheduled = false;
      nlsFollowActive = false;
      // Don't yank VERIFYING/STRIKING/COOLDOWN back to idle on arm TTL alone
      if (racePhase == RacePhase.ARMED || racePhase == RacePhase.IDLE) {
        setRacePhase(RacePhase.IDLE, "arm-expire");
      }
      finishRapidoMicroBurst("arm-expire");
      clearStaleAcceptTapPoints("arm-expire");
      // Captain FG / standby: KEEP hunting for the next Accept (back-to-back).
      // Only stop when not on Captain, not standby, and race is idle.
      if (!isRaceActive()) {
        if (rapidoForeground || pendingRideSignal || isStandbyHunting()
            || hasNonBubbleRapidoWindow()) {
          resumeHuntAfterGap("arm-expire-fg");
        } else {
          stopAcceptHuntPoll("arm-expire");
          clearCachedAcceptPoint("arm-expire");
        }
      }
    }
  };

  /**
   * Ola: fire Shizuku the moment the slider reveals Accept.
   * Does not go through smartClick — burst/pkg/race gates were swallowing the tap.
   */
  private final Runnable olaUnlockStrikeRunnable = new Runnable() {
    @Override
    public void run() {
      olaUnlockStrikeScheduled = false;
      if (!engineIsOn()) return;
      long t0 = SystemClock.uptimeMillis();

      AccessibilityNodeInfo live = findAcceptNodeAnywhere();
      if (live == null) {
        if (olaUnlockDeferCount < OLA_UNLOCK_DEFER_MAX) {
          olaUnlockDeferCount++;
          olaUnlockStrikeScheduled = true;
          handler.postDelayed(this, 80L);
          Log.w(TAG, "OLA_UNLOCK_RETRY no-live-accept n=" + olaUnlockDeferCount);
        } else {
          Log.w(TAG, "OLA_UNLOCK_ABORT no-live-accept");
          clearCachedAcceptPoint("ola-unlock-gone");
        }
        return;
      }
      String nodePkg = packageOf(live);
      if (AutoClickerConfig.isCaptainRapidoPackage(nodePkg)) {
        try { live.recycle(); } catch (Exception ignored) {}
        Log.w(TAG, "OLA_UNLOCK_ABORT other-app pkg=" + nodePkg);
        return;
      }
      CharSequence label = nodeTextCs(live);
      if (!isRealAcceptLabel(label) && !viewIdLooksLikeAccept(live)) {
        try { live.recycle(); } catch (Exception ignored) {}
        Log.w(TAG, "OLA_UNLOCK_ABORT not-accept-label");
        return;
      }
      try {
        live.getBoundsInScreen(scratchRect);
        if (scratchRect.isEmpty() || isOversizedAcceptBounds(scratchRect)) {
          try { live.recycle(); } catch (Exception ignored) {}
          Log.w(TAG, "OLA_UNLOCK_ABORT bad-bounds");
          return;
        }
      } catch (Exception e) {
        try { live.recycle(); } catch (Exception ignored) {}
        return;
      }

      int countdown = parseAcceptInCountdown(label);
      if (countdown > 0) {
        olaSawCountdownThisRace = true;
        if (olaUnlockDeferCount < OLA_UNLOCK_DEFER_MAX) {
          olaUnlockDeferCount++;
          long waitMs = countdown >= 2 ? 300L : 120L;
          olaUnlockStrikeScheduled = true;
          handler.postDelayed(this, waitMs);
          Log.i(TAG, "OLA_UNLOCK_DEFER countdown=" + countdown
              + " n=" + olaUnlockDeferCount
              + " inMs=" + waitMs
              + " label=" + label);
        } else {
          Log.w(TAG, "OLA_UNLOCK_ABORT still-counting label=" + label);
        }
        try { live.recycle(); } catch (Exception ignored) {}
        return;
      }
      if (!isOlaAcceptUnlocked(live, label)) {
        if (olaUnlockDeferCount < OLA_UNLOCK_DEFER_MAX) {
          olaUnlockDeferCount++;
          olaUnlockStrikeScheduled = true;
          handler.postDelayed(this, 80L);
        } else {
          Log.w(TAG, "OLA_UNLOCK_ABORT not-ready label=" + label);
        }
        try { live.recycle(); } catch (Exception ignored) {}
        return;
      }

      if (!offerPassesFilters(live)) {
        Log.i(TAG, "OLA_UNLOCK_SKIP filter fare=" + lastRideFare
            + " pickup=" + lastRidePickupKm);
        try { live.recycle(); } catch (Exception ignored) {}
        return;
      }

      int cx = scratchRect.centerX();
      int cy = scratchRect.centerY();
      Log.i(TAG, "OLA_UNLOCK_FIRE waitedMs="
          + (olaAcceptFirstSeenMs > 0 ? (t0 - olaAcceptFirstSeenMs) : -1)
          + " label=" + label
          + " @" + cx + "," + cy
          + " shizuku=" + ShizukuInput.isReady());
      injectOlaAcceptAt(cx, cy, live, true);
      try { live.recycle(); } catch (Exception ignored) {}
      scheduleAcceptHuntPollImmediate();
    }
  };

  /**
   * When hunt misses at unlock — only strike if a live Accept is still near cache.
   * Never swipe the launcher with stale coords (moves home icons).
   */
  private boolean strikeOlaCachedUnlock(String pkg, long t0) {
    if (!cachedAcceptValid || cachedAcceptX <= 0 || cachedAcceptY <= 0) return false;
    long age = SystemClock.uptimeMillis() - cachedAcceptAtMs;
    if (cachedAcceptAtMs <= 0 || age > 12_000L) {
      Log.w(TAG, "OLA_CACHED_ABORT stale ageMs=" + age);
      return false;
    }
    AccessibilityNodeInfo live = findAcceptNodeAnywhere();
    if (live == null) {
      Log.w(TAG, "OLA_CACHED_ABORT no-live-accept");
      clearCachedAcceptPoint("ola-cache-gone");
      return false;
    }
    try {
      live.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()
          || (!isRealAcceptLabel(nodeTextCs(live)) && !viewIdLooksLikeAccept(live))) {
        try { live.recycle(); } catch (Exception ignored) {}
        return false;
      }
      int cx = scratchRect.centerX();
      int cy = scratchRect.centerY();
      // Must be near warmed point — otherwise refuse (wrong UI)
      if (Math.abs(cx - cachedAcceptX) > 120 || Math.abs(cy - cachedAcceptY) > 120) {
        Log.w(TAG, "OLA_CACHED_ABORT moved @" + cx + "," + cy
            + " cache=" + cachedAcceptX + "," + cachedAcceptY);
        // Still allow strike on the LIVE Accept we found
      }
      TapHighlightOverlay.hideImmediate();
      Log.w(TAG, "OLA_CACHED_STRIKE @" + cx + "," + cy
          + " label=" + nodeTextCs(live)
          + " shizuku=" + ShizukuInput.isReady());
      if (!canStartRapidoBurst() && rapidoBurstLock) {
        finishRapidoMicroBurst("ola-unlock-force");
      }
      return smartClickAccept(live, pkg != null ? pkg : AutoClickerConfig.PKG_OLA_DRIVER,
          "OlaCachedLive", t0);
    } catch (Exception e) {
      try { live.recycle(); } catch (Exception ignored) {}
      return false;
    }
  }

  /**
   * Ola Accept: Shizuku press after the 5s slider reveals Accept.
   * A11y / gesture do not register. At most two presses this race.
   */
  private boolean injectOlaAcceptAt(int cx, int cy, AccessibilityNodeInfo node) {
    return injectOlaAcceptAt(cx, cy, node, false);
  }

  private boolean injectOlaAcceptAt(
      int cx, int cy, AccessibilityNodeInfo node, boolean forceUnlocked) {
    if (!AutoClickerConfig.peekEnabled()) {
      return false;
    }
    if (cx <= 0 || cy <= 0) {
      return false;
    }
    if (olaShizukuTapsThisRace >= OLA_MAX_SHIZUKU_TAPS) {
      Log.w(TAG, "OLA_INJECT_SKIP already-fired n=" + olaShizukuTapsThisRace
          + " @" + cx + "," + cy);
      return false;
    }
    boolean ownNode = false;
    int bw = 0;
    int bh = 0;
    if (node == null) {
      AccessibilityNodeInfo live = findAcceptNearPoint(cx, cy, 100);
      if (live != null) {
        node = live;
        ownNode = true;
      }
    }
    if (node != null) {
      try {
        node.getBoundsInScreen(scratchRect);
        if (!scratchRect.isEmpty() && !isOversizedAcceptBounds(scratchRect)) {
          bw = scratchRect.width();
          bh = scratchRect.height();
          // Clickable parent often has no "Accept" text — still use its button bounds
          cx = scratchRect.centerX();
          cy = scratchRect.centerY();
        }
      } catch (Exception ignored) {
      }
    }

    try {
      CharSequence label = node != null ? nodeTextCs(node) : "";
      int countdown = parseAcceptInCountdown(label);
      if (countdown > 0) {
        Log.w(TAG, "OLA_INJECT_ABORT still-locked label=" + label);
        return false;
      }
      if (!forceUnlocked && !isOlaAcceptUnlocked(node, label)) {
        Log.w(TAG, "OLA_INJECT_ABORT not-ready label=" + label);
        return false;
      }
      if (!offerPassesFilters(node)) {
        Log.i(TAG, "OLA_INJECT_SKIP filter fare=" + lastRideFare
            + " pickup=" + lastRidePickupKm);
        return false;
      }

      TapHighlightOverlay.hideImmediate();

      if (!ensureShizukuReady()) {
        Log.w(TAG, "OLA_INJECT_ABORT shizuku-not-ready @" + cx + "," + cy
            + " state=" + ShizukuInput.state(getApplicationContext()));
        return false;
      }

      // Text node sits on the top of a taller CTA — press into the button body
      int tapX = cx;
      int tapY = cy + Math.max(6, bh / 5);
      olaShizukuTapsThisRace++;
      boolean ok = ShizukuInput.tapConfirmed(tapX, tapY, OLA_ONE_SHOT_TAP_MS);

      TapHighlightOverlay.showPoint(this, tapX, tapY, 10, 500L);

      if (ok && olaShizukuTapsThisRace < OLA_MAX_SHIZUKU_TAPS) {
        final int rx = tapX;
        final int ry = tapY;
        handler.postDelayed(() -> {
          if (olaShizukuTapsThisRace >= OLA_MAX_SHIZUKU_TAPS) return;
          if (!acceptStillVisibleAnywhere()) return;
          if (!ShizukuInput.isReady()) return;
          TapHighlightOverlay.hideImmediate();
          olaShizukuTapsThisRace++;
          boolean retry = ShizukuInput.tapConfirmed(rx, ry, OLA_ONE_SHOT_TAP_MS);
          Log.w(TAG, "OLA_INJECT_RETRY @" + rx + "," + ry + " ok=" + retry);
        }, 240L);
      }

      Log.w(TAG, "OLA_INJECT @" + tapX + "," + tapY
          + " labelCenter=" + cx + "," + cy
          + " wh=" + bw + "x" + bh
          + " ok=" + ok
          + " n=" + olaShizukuTapsThisRace
          + " mode=shizuku-oneshot"
          + " label=" + label
          + " shizuku=true"
          + " active=" + resolveActivePackage());
      return ok;
    } finally {
      if (ownNode && node != null) {
        try { node.recycle(); } catch (Exception ignored) {}
      }
    }
  }

  /** Live Accept near a point, or null. Caller owns returned node. */
  private AccessibilityNodeInfo findAcceptNearPoint(int cx, int cy, int maxDist) {
    AccessibilityNodeInfo live = findAcceptNodeAnywhere();
    if (live == null) return null;
    try {
      live.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()
          || (!isRealAcceptLabel(nodeTextCs(live)) && !viewIdLooksLikeAccept(live))) {
        live.recycle();
        return null;
      }
      if (Math.abs(scratchRect.centerX() - cx) > maxDist
          || Math.abs(scratchRect.centerY() - cy) > maxDist) {
        // Still return live Accept — better than stale point (caller may use its center)
        return live;
      }
      return live;
    } catch (Exception e) {
      try { live.recycle(); } catch (Exception ignored) {}
      return null;
    }
  }

  /** Re-bind Shizuku in the :engine process before Ola inject. */
  private boolean ensureShizukuReady() {
    Context ctx = getApplicationContext();
    ShizukuInput.attach(ctx != null ? ctx : this);
    return ShizukuInput.isReady();
  }

  /** Start / restart coalesced NLS follow-ups (cancels prior chain). */
  private void scheduleNlsFollowups(String packageName, long tReceive) {
    handler.removeCallbacks(nlsFollowRunnable);
    nlsFollowPkg = packageName;
    nlsFollowT0 = tReceive;
    nlsFollowIndex = 0;
    nlsFollowActive = true;
    if (nlsFollowDelays.length == 0) {
      nlsFollowActive = false;
      return;
    }
    handler.postDelayed(nlsFollowRunnable, nlsFollowDelays[0]);
  }

  /**
   * Device profile: keep the same find→Accept→click logic on every phone, but tune
   * gesture length / arm / NLS follow-ups so heavy OEMs register taps and late overlays.
   */
  private void detectAndApplyLowEndProfile() {
    detectAndApplyDeviceProfile();
  }

  private void detectAndApplyDeviceProfile() {
    boolean low = false;
    try {
      ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
      if (am != null && am.isLowRamDevice()) low = true;
    } catch (Exception ignored) {
    }
    try {
      int cores = Runtime.getRuntime().availableProcessors();
      if (cores > 0 && cores <= 4) low = true;
    } catch (Exception ignored) {
    }
    lowEndDevice = low;

    String m = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "").toLowerCase(Locale.US);
    String b = (Build.BRAND != null ? Build.BRAND : "").toLowerCase(Locale.US);
    String fp = (Build.FINGERPRINT != null ? Build.FINGERPRINT : "").toLowerCase(Locale.US);
    String disp = (Build.DISPLAY != null ? Build.DISPLAY : "").toLowerCase(Locale.US);
    // Skins that delay a11y / swallow short taps
    heavyOem = m.contains("oppo") || m.contains("realme") || m.contains("oneplus")
        || m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
        || m.contains("vivo") || m.contains("iqoo") || m.contains("huawei")
        || m.contains("honor") || m.contains("tecno") || m.contains("infinix")
        || m.contains("itel") || m.contains("transsion")
        || b.contains("oppo") || b.contains("realme") || b.contains("xiaomi")
        || b.contains("vivo") || b.contains("iqoo") || b.contains("redmi")
        || b.contains("poco") || b.contains("tecno") || b.contains("infinix")
        || fp.contains("funtouch") || fp.contains("originos") || fp.contains("coloros")
        || fp.contains("realmeui") || fp.contains("miui") || fp.contains("hyperos")
        || disp.contains("funtouch") || disp.contains("coloros") || disp.contains("origin");
    // Samsung One UI: keep stock timing (short tap wins); not heavyOem

    treeWalkCap = TREE_WALK_CAP;
    acceptHuntPollMs = ACCEPT_HUNT_POLL_MS;
    rapidoMicroIntervalMs = RAPIDO_MICRO_INTERVAL_MS;

    // Tap length is short+long on every phone. OEM profile only keeps late NLS pulses.
    rapidoGestureMs = TAP_MS_LONG;
    if (heavyOem) {
      rapidoMicroExtraStrikes = RAPIDO_MICRO_EXTRA_HEAVY;
      armedBgHuntPollMs = ARMED_BG_HUNT_POLL_HEAVY_MS;
      nlsFollowDelays = NLS_FOLLOW_DELAYS_HEAVY_MS;
      raceArmMs = Math.max(RACE_ARM_MS, 7000L);
    } else {
      rapidoMicroExtraStrikes = RAPIDO_MICRO_EXTRA;
      armedBgHuntPollMs = ARMED_BG_HUNT_POLL_MS;
      nlsFollowDelays = NLS_FOLLOW_DELAYS_MS;
      raceArmMs = RACE_ARM_MS;
    }
    // Never inflate first-press for "low RAM" — that loses the server race
    Log.i(TAG, "DEVICE_PROFILE heavyOem=" + heavyOem
        + " gestMs=" + rapidoGestureMs
        + " armMs=" + raceArmMs
        + " pollMs=" + acceptHuntPollMs
        + " mfr=" + Build.MANUFACTURER
        + " brand=" + Build.BRAND
        + " lowEnd=" + lowEndDevice);
  }

  /** Restore last Accept center from disk so NLS can strike before the tree paints. */
  private void hydrateCachedAcceptFromDisk() {
    try {
      for (String pkg : new String[] {
          AutoClickerConfig.PKG_RAPIDO_CAPTAIN,
          AutoClickerConfig.PKG_RAPIDO_CAPTAIN_LEGACY,
          AutoClickerConfig.PKG_OLA_DRIVER
      }) {
        int[] pt = AutoClickerConfig.getCachedTapPoint(pkg);
        if (pt == null || pt.length < 2 || pt[0] <= 0) {
          pt = AutoClickerConfig.getOverlayTapPoint(pkg);
        }
        if (pt != null && pt.length >= 2 && pt[0] > 0 && pt[1] > 0) {
          cachedAcceptX = pt[0];
          cachedAcceptY = pt[1];
          cachedAcceptPkg = pkg;
          cachedAcceptValid = true;
          cachedAcceptAtMs = SystemClock.uptimeMillis();
          Log.i(TAG, "CACHE_HYDRATE @" + pt[0] + "," + pt[1] + " pkg=" + pkg);
          return;
        }
      }
    } catch (Exception ignored) {
    }
  }

  /**
   * Instant exact-center tap at last known Accept — only when Accept is still live.
   * Never blind-tap stale Home/WhatsApp coords.
   */
  private void fireCachedAcceptStrike(String pkg, long t0, String source) {
    if (!cachedAcceptValid || cachedAcceptX <= 0 || cachedAcceptY <= 0) return;
    long age = SystemClock.uptimeMillis() - cachedAcceptAtMs;
    if (cachedAcceptAtMs <= 0 || age > CACHED_ACCEPT_STRIKE_MAX_AGE_MS) return;
    if (isRapidoInteractionBlocked() || !canStartRapidoBurst()) return;
    // Fast path: if Accept node is live near cache, use smartClick (full dual-strike)
    AccessibilityNodeInfo live = null;
    try {
      live = findAcceptNodeAnywhere();
      if (live != null) {
        live.getBoundsInScreen(scratchRect);
        int x = cachedAcceptX;
        int y = cachedAcceptY;
        if (!scratchRect.isEmpty()
            && !isExtremeTopChromeAccept(scratchRect)
            && !isBubbleLikeClickTarget(scratchRect)
            && Math.abs(scratchRect.centerX() - x) <= Math.max(96, scratchRect.width())
            && Math.abs(scratchRect.centerY() - y) <= Math.max(96, scratchRect.height())) {
          String usePkg = pkg != null ? pkg : cachedAcceptPkg;
          smartClickAccept(live, usePkg, "CacheLive/" + source, t0);
          live = null; // owned by smartClick
          return;
        }
      }
    } catch (Exception ignored) {
    } finally {
      if (live != null) {
        try { live.recycle(); } catch (Exception ignored) {}
      }
    }
  }

  /**
   * Instant 1ms spray at last Accept pixel — starts on the NLS thread, before
   * any tree walk. Other tappers win by tapping this pixel the moment the
   * alert arrives; we used to wait to FIND the node first.
   */
  private void firePredictiveRapidoTap(String pkg, long t0, String source) {
    startAlertSpray(pkg, t0, source);
  }

  private void startAlertSpray(String pkg, long t0, String source) {
    if (pkg == null || !AutoClickerConfig.isCaptainRapidoPackage(pkg)) return;
    if (!AutoClickerConfig.peekEnabled()) return;

    int x = cachedAcceptX;
    int y = cachedAcceptY;
    if (!cachedAcceptValid || x <= 0 || y <= 0) return;
    if (cachedAcceptPkg != null
        && !AutoClickerConfig.isCaptainRapidoPackage(cachedAcceptPkg)) return;
    if (cachedAcceptAtMs <= 0) return;
    long age = SystemClock.uptimeMillis() - cachedAcceptAtMs;
    if (age > PREDICTIVE_CACHE_MAX_AGE_MS) return;
    if (pointInsideKnownBubble(x, y)) return;

    alertSprayX = x;
    alertSprayY = y;
    predictivePulseX = x;
    predictivePulseY = y;
    alertSprayUntilMs = SystemClock.uptimeMillis() + ALERT_SPRAY_WINDOW_MS;
    alertSprayLeft = ALERT_SPRAY_MAX_TAPS;
    alertSprayFails = 0;
    alertSprayActive = true;
    lastPredictiveTapMs = SystemClock.uptimeMillis();
    ShizukuInput.startCoordSpray(x, y, ALERT_SPRAY_WINDOW_MS);
    dispatchAlertSprayTap();
    Log.w(TAG, "ALERT_SPRAY @" + x + "," + y
        + " src=" + source
        + " ms=" + (SystemClock.uptimeMillis() - t0));
  }

  private void continueAlertSpray(String reason) {
    if (!alertSprayActive) return;
    if (acceptSuccessLatch || !engineIsOn() || isRapidoInteractionBlocked()) {
      stopAlertSpray();
      return;
    }
    long now = SystemClock.uptimeMillis();
    if (now >= alertSprayUntilMs || alertSprayLeft <= 0) {
      stopAlertSpray();
      return;
    }
    if (alertSprayX <= 0 || alertSprayY <= 0 || pointInsideKnownBubble(alertSprayX, alertSprayY)) {
      stopAlertSpray();
      return;
    }
    dispatchAlertSprayTap();
  }

  private void dispatchAlertSprayTap() {
    int x = alertSprayX;
    int y = alertSprayY;
    if (x <= 0 || y <= 0) {
      stopAlertSpray();
      return;
    }
    alertSprayLeft--;
    boolean ok = dispatchGestureTapIndependent(x, y, ALERT_SPRAY_TAP_MS, alertSprayCb);
    if (ok) {
      alertSprayFails = 0;
      return;
    }
    alertSprayFails++;
    if (alertSprayFails >= 12 || alertSprayLeft <= 0) {
      stopAlertSpray();
      return;
    }
    handler.removeCallbacks(predictivePulseRunnable);
    handler.postAtFrontOfQueue(predictivePulseRunnable);
  }

  private void stopAlertSpray() {
    alertSprayActive = false;
    alertSprayLeft = 0;
    handler.removeCallbacks(predictivePulseRunnable);
    ShizukuInput.stopCoordSpray();
  }

  private void cancelPredictivePulses() {
    predictivePulseLeft = 0;
    stopAlertSpray();
  }

  /** True when UI is the Missed-order card (Accept still drawn but ride is dead). */
  private boolean rootLooksLikeMissedOffer(AccessibilityNodeInfo root) {
    if (root == null) return false;
    try {
      String t = dumpText(root);
      if (t == null || t.isEmpty()) return false;
      String h = t.toLowerCase(Locale.US);
      if (h.contains("you missed the order")) return true;
      if (h.contains("missed the order")) return true;
      if (h.contains("accepted by another captain")) return true;
      if (h.contains("accepted by another")) return true;
      if (h.contains("order was accepted by another")) return true;
      if (h.contains("order expired") || h.contains("offer expired")) return true;
      if (h.contains("no longer available") || h.contains("not available")) return true;
      if (h.contains("ride cancelled") || h.contains("booking cancelled")) return true;
      if (h.contains("already taken") || h.contains("taken by another")) return true;
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean rootLooksLikeMissedOfferAnywhere() {
    long now = SystemClock.uptimeMillis();
    if (now - lastMissedScanAtMs < MISSED_SCAN_THROTTLE_MS) {
      return lastMissedScanHit;
    }
    lastMissedScanAtMs = now;
    boolean hit = false;
    AccessibilityNodeInfo active = null;
    try {
      active = getRootInActiveWindow();
      if (rootLooksLikeMissedOffer(active)) hit = true;
    } catch (Exception ignored) {
    } finally {
      if (active != null) {
        try { active.recycle(); } catch (Exception ignored) {}
      }
    }
    if (!hit) {
      try {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
          for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            AccessibilityNodeInfo root = null;
            try {
              root = w.getRoot();
              if (root != null && allowAsRapidoWindow(packageOf(root), lastPkg)
                  && rootLooksLikeMissedOffer(root)) {
                hit = true;
                break;
              }
            } catch (Exception ignored) {
            } finally {
              if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
              }
            }
          }
        }
      } catch (Exception ignored) {
      }
    }
    lastMissedScanHit = hit;
    return hit;
  }

  private void syncEngineForeground() {
    // Always show a status notification while a11y is bound so OFF is obvious
    startEngineForeground();
  }

  private void startEngineForeground() {
    try {
      ensureFgChannel();
      int icon = getApplicationInfo().icon;
      if (icon == 0) icon = android.R.drawable.ic_dialog_info;
      boolean on = AutoClickerConfig.isEnabled();
      Intent open = new Intent(this, MainActivity.class);
      open.setAction(Intent.ACTION_MAIN);
      open.addCategory(Intent.CATEGORY_LAUNCHER);
      open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        piFlags |= PendingIntent.FLAG_IMMUTABLE;
      }
      PendingIntent contentPi = PendingIntent.getActivity(this, 7142, open, piFlags);
      Notification.Builder b;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        b = new Notification.Builder(this, FG_CHANNEL_ID);
      } else {
        b = new Notification.Builder(this);
      }
      Notification n = b
          .setContentTitle(on ? "AG rider — Auto-accept ON" : "AG rider — Auto-accept OFF")
          .setContentText(on
              ? (lowEndDevice ? "Hunting Accept — tap to open app" : "Hunting Accept — tap to open app")
              : "Tap to open AG rider and turn Auto-accept ON")
          .setSmallIcon(icon)
          .setContentIntent(contentPi)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .build();
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(FG_NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      } else {
        startForeground(FG_NOTIFY_ID, n);
      }
      foregroundStarted = true;
    } catch (Exception e) {
      Log.w(TAG, "ENGINE_FOREGROUND failed: " + e.getMessage());
    }
  }

  private void stopEngineForeground() {
    if (AutoClickerConfig.peekEnabled() || AutoClickerConfig.isEnabled()) {
      EngineKeepAlive.ensureStarted(this);
      return;
    }
    if (!foregroundStarted) return;
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE);
      } else {
        stopForeground(true);
      }
    } catch (Exception ignored) {
    }
    foregroundStarted = false;
  }

  private void ensureFgChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    try {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm == null) return;
      if (nm.getNotificationChannel(FG_CHANNEL_ID) != null) return;
      NotificationChannel ch = new NotificationChannel(
          FG_CHANNEL_ID,
          "AG rider engine",
          NotificationManager.IMPORTANCE_LOW
      );
      ch.setDescription("Keeps Accept race engine alive on low-RAM devices");
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    } catch (Exception ignored) {
    }
  }

  private void ensureScreenMetrics() {
    long now = SystemClock.uptimeMillis();
    if (screenArea > 0 && now - screenMetricsAtMs < SCREEN_METRICS_TTL_MS) return;
    try {
      android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
      screenW = dm.widthPixels;
      screenH = dm.heightPixels;
      density = dm.density > 0f ? dm.density : 3f;
      screenArea = (float) screenW * (float) screenH;
      screenMetricsAtMs = now;
    } catch (Exception ignored) {
    }
  }

  private int dp(int v) {
    ensureScreenMetrics();
    return (int) (v * density + 0.5f);
  }

  /** Aspect ratio (larger/smaller) for near-square chat-head detection. */
  private static float bubbleAspect(int w, int h) {
    if (w <= 0 || h <= 0) return 999f;
    return w >= h ? (float) w / (float) h : (float) h / (float) w;
  }

  private static boolean isNearSquareBubbleAspect(float aspect) {
    return aspect >= BUBBLE_ASPECT_MIN && aspect <= BUBBLE_ASPECT_MAX;
  }

  /**
   * Wide ride card / bottom sheet — never classify as float-icon bubble.
   * Captain home sheets are often moderate height but ≥50% screen (or ≥280dp) wide.
   */
  private boolean isRideCardSizedBounds(Rect r) {
    if (r == null || r.isEmpty()) return false;
    ensureScreenMetrics();
    int w = r.width();
    if (w >= dp(RIDE_CARD_MIN_WIDTH_DP)) return true;
    return screenW > 0 && w >= screenW * RIDE_CARD_WIDTH_FRAC;
  }

  /**
   * Chat-head / floating Rapido launcher window — never a ride Accept card.
   * Uses dp so xxhdpi HyperOS bubbles and mdpi stock icons both reject.
   * Wide sheets (≥50% / ≥280dp) skip compact rules; Accept presence is required
   * separately before any click/hunt treats them as ride surfaces.
   */
  private boolean isFloatingBubbleBounds(Rect r) {
    if (r == null || r.isEmpty()) return true;
    ensureScreenMetrics();
    int w = r.width();
    int h = r.height();
    // Wide ride card / bottom sheet: not a float icon by geometry alone
    if (isRideCardSizedBounds(r)) return false;

    int maxBubble = dp(BUBBLE_MAX_DP);
    int squareMax = dp(BUBBLE_SQUARE_MAX_DP);
    float aspect = bubbleAspect(w, h);

    // Both sides ≤ ~180dp → float icon (any aspect)
    if (w <= maxBubble && h <= maxBubble) return true;

    // Near-square chat-head (max side ≤ ~200dp, aspect ~0.65–1.4)
    if (Math.max(w, h) <= squareMax && isNearSquareBubbleAspect(aspect)) return true;

    // Degenerate strip chrome (window-level only — not Accept CTAs)
    if (w < dp(80) || (h < dp(80) && w < screenW * 0.40f)) return true;

    // Small overlay: area < 12% AND width < 50% AND height < 40% → float icon
    if (screenArea > 0f && screenW > 0 && screenH > 0) {
      float area = (float) w * (float) h;
      if (area > 0f
          && area < screenArea * BUBBLE_AREA_FRAC
          && w < screenW * RIDE_CARD_WIDTH_FRAC
          && h < screenH * BUBBLE_AREA_HEIGHT_FRAC) {
        return true;
      }
    }
    return false;
  }

  /**
   * Node / tap-target bubble check. Compact near-square icon only —
   * does NOT reject wide×short Accept buttons (both sides may be ≤180dp but aspect is flat).
   */
  private boolean isBubbleLikeClickTarget(Rect r) {
    if (r == null || r.isEmpty()) return true;
    // Wide Accept CTAs / sheet rows — never treat as float icon
    if (isRideCardSizedBounds(r)) return false;
    ensureScreenMetrics();
    int w = r.width();
    int h = r.height();
    int maxBubble = dp(BUBBLE_MAX_DP);
    int squareMax = dp(BUBBLE_SQUARE_MAX_DP);
    float aspect = bubbleAspect(w, h);

    // Compact near-square (icon), not a flat Accept bar
    if (w <= maxBubble && h <= maxBubble && isNearSquareBubbleAspect(aspect)) return true;
    // Near-square padded chat-head (max ≤ ~200dp)
    if (Math.max(w, h) <= squareMax && isNearSquareBubbleAspect(aspect)) return true;

    // Icon-tiny area (≪ Accept CTA)
    if (screenArea > 0f && screenW > 0 && screenH > 0) {
      float area = (float) w * (float) h;
      if (area > 0f
          && area < screenArea * BUBBLE_AREA_FRAC
          && w < screenW * RIDE_CARD_WIDTH_FRAC
          && h < screenH * BUBBLE_AREA_HEIGHT_FRAC
          && isNearSquareBubbleAspect(aspect)) {
        return true;
      }
    }
    return false;
  }

  /** True if this node's bounds or its window look like the float icon. */
  private boolean isFloatingBubbleNode(AccessibilityNodeInfo node) {
    if (node == null) return true;
    try {
      node.getBoundsInScreen(scratchRect);
      if (isBubbleLikeClickTarget(scratchRect)) return true;
    } catch (Exception ignored) {
    }
    try {
      AccessibilityWindowInfo w = node.getWindow();
      if (w != null) {
        w.getBoundsInScreen(scratchRect2);
        if (isFloatingBubbleBounds(scratchRect2)) {
          noteBubbleWindow(scratchRect2);
          return true;
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /** Remember a Rapido float-icon window for cheap gesture/cache gates. */
  private void noteBubbleWindow(Rect r) {
    if (r == null || r.isEmpty()) return;
    lastBubbleWindowRect.set(r);
    lastBubbleWindowValid = true;
    lastBubbleSeenAtMs = SystemClock.uptimeMillis();
  }

  private boolean pointInsideKnownBubble(int x, int y) {
    if (!lastBubbleWindowValid || x <= 0 || y <= 0) return false;
    if (SystemClock.uptimeMillis() - lastBubbleSeenAtMs > BUBBLE_STICKY_MS) {
      lastBubbleWindowValid = false;
      return false;
    }
    return lastBubbleWindowRect.contains(x, y);
  }

  /** True if (x,y) sits inside any Rapido-sized float-icon window. */
  private boolean pointInsideRapidoBubbleWindow(int x, int y) {
    if (x <= 0 || y <= 0) return false;
    if (pointInsideKnownBubble(x, y)) return true;
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return false;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        try {
          w.getBoundsInScreen(scratchRect);
          if (!isFloatingBubbleBounds(scratchRect)) continue;
          if (!scratchRect.contains(x, y)) continue;
          AccessibilityNodeInfo root = w.getRoot();
          if (root == null) continue;
          try {
            if (allowAsRapidoWindow(packageOf(root), lastPkg)) {
              noteBubbleWindow(scratchRect);
              return true;
            }
          } finally {
            root.recycle();
          }
        } catch (Exception ignored) {
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /** Hard latch during COOLDOWN only — expires into IDLE so next ride can arm. */
  private boolean isRapidoInteractionBlocked() {
    long now = SystemClock.uptimeMillis();
    if (now < ignoreRapidoUntilMs) return true;
    // COOLDOWN window over — return to IDLE (latch must not stick forever)
    if (racePhase == RacePhase.COOLDOWN || acceptSuccessLatch) {
      acceptSuccessLatch = false;
      if (racePhase == RacePhase.COOLDOWN) {
        setRacePhase(RacePhase.IDLE, "cooldown-expired");
      }
      // Back-to-back: resume hunt immediately (Captain FG / pending NLS)
      resumeHuntAfterGap("cooldown-expired");
    }
    return false;
  }

  /**
   * Drop post-Accept latch so the next instant overlay can race.
   * Does not clear last Accept x,y — those coords win the next ping.
   */
  private void breakRapidoCooldownForNewRide() {
    acceptSuccessLatch = false;
    ignoreRapidoUntilMs = 0;
    rapidoCooldownUntilMs = 0;
    rapidoRetryUntilMs = 0;
    rapidoBurstLock = false;
    verifyingAccept = false;
    if (racePhase == RacePhase.COOLDOWN
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING) {
      cancelVerify("new-ride");
      finishRapidoMicroBurst("new-ride");
      setRacePhase(RacePhase.ARMED, "new-ride");
    }
  }

  private void setRacePhase(RacePhase next, String reason) {
    if (next == null) next = RacePhase.IDLE;
    RacePhase prev = racePhase;
    if (prev == next) return;
    racePhase = next;
    racePhaseEnteredAtMs = SystemClock.uptimeMillis();
    Log.i(TAG, "PHASE " + prev + "→" + next + " (" + reason + ")");
  }

  /**
   * After COOLDOWN / arm-expire: keep hunting for the next Accept (back-to-back).
   * Replays any ride signal that arrived while the latch was held.
   */
  private void resumeHuntAfterGap(String reason) {
    acceptSuccessLatch = false;
    if (SystemClock.uptimeMillis() >= ignoreRapidoUntilMs) {
      ignoreRapidoUntilMs = 0;
      rapidoCooldownUntilMs = 0;
    }
    flushPendingRideSignal(reason);
    if (shouldRunAcceptHuntPoll()) {
      scheduleAcceptHuntPoll();
      Log.i(TAG, "HUNT_RESUME " + reason
          + " fg=" + rapidoForeground
          + " phase=" + racePhase);
    }
  }

  private void flushPendingRideSignal(String reason) {
    if (!pendingRideSignal) return;
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    String pkg = pendingRidePkg;
    String text = pendingRideText;
    String src = pendingRideSource != null ? pendingRideSource : "pending";
    Notification n = pendingRideNotification;
    long t0 = pendingRideAtMs > 0 ? pendingRideAtMs : SystemClock.uptimeMillis();
    pendingRideSignal = false;
    pendingRidePkg = null;
    pendingRideText = null;
    pendingRideSource = null;
    pendingRideNotification = null;
    pendingRideAtMs = 0L;
    if (pkg == null) return;
    Log.i(TAG, "PENDING_RIDE_FLUSH reason=" + reason + " src=" + src + " pkg=" + pkg);
    onRideSignal(pkg, n, text, t0, src + "/flush");
  }

  private void deferRideSignal(
      String pkg, Notification n, String text, long t0, String source
  ) {
    pendingRideSignal = true;
    pendingRidePkg = pkg;
    pendingRideText = text;
    pendingRideSource = source;
    pendingRideAtMs = t0;
    pendingRideNotification = n;
    long delay = Math.max(30L, ignoreRapidoUntilMs - SystemClock.uptimeMillis() + 20L);
    handler.removeCallbacks(pendingRideFlushRunnable);
    handler.postDelayed(pendingRideFlushRunnable, delay);
    Log.i(TAG, "PENDING_RIDE_DEFER src=" + source + " delayMs=" + delay);
  }

  private final Runnable pendingRideFlushRunnable = new Runnable() {
    @Override
    public void run() {
      isRapidoInteractionBlocked(); // expire latch if due
      flushPendingRideSignal("timer");
      if (shouldRunAcceptHuntPoll()) scheduleAcceptHuntPoll();
    }
  };

  /** Bring Captain FG when overlay a11y hunt misses (once per ride signal). */
  private final Runnable contentIntentFallbackRunnable = new Runnable() {
    @Override
    public void run() {
      tryContentIntentFallback("timer");
    }
  };

  private void cancelContentIntentFallback(String reason) {
    handler.removeCallbacks(contentIntentFallbackRunnable);
    if (RACE_DEBUG_LOG && reason != null) {
      Log.i(TAG, "CONTENT_INTENT_CANCEL " + reason);
    }
  }

  /**
   * Schedule a single contentIntent open if Accept still not struck and Captain is BG.
   * Skipped when Accept PendingIntent action already fired (verify owns that path).
   */
  private void scheduleContentIntentFallbackIfNeeded(
      String pkg,
      Notification notification,
      long tReceive,
      boolean pendingIntentActionOk
  ) {
    cancelContentIntentFallback("reschedule");
    pendingContentIntentNotif = notification;
    contentIntentSignalPkg = pkg;
    contentIntentSignalT0 = tReceive;
    contentIntentSentThisSignal = false;
    acceptFoundThisSignal = false;

    if (notification == null || notification.contentIntent == null) return;
    if (rapidoForeground) return;
    if (pendingIntentActionOk) return;

    handler.postDelayed(contentIntentFallbackRunnable, CONTENT_INTENT_FALLBACK_MS);
  }

  /**
   * Open Rapido via notification contentIntent — guarded fallback for OEMs where
   * overlay Accept is not exposed to Accessibility until Captain is foreground.
   */
  private void tryContentIntentFallback(String reason) {
    if (!engineIsOn()) return;
    if (contentIntentSentThisSignal) return;
    if (acceptFoundThisSignal) return;
    if (rapidoForeground) return;
    if (rapidoBurstLock || verifyingAccept || racePhase == RacePhase.STRIKING) return;
    if (!isRaceArmed() && racePhase != RacePhase.ARMED) return;
    if (pendingContentIntentNotif == null || pendingContentIntentNotif.contentIntent == null) {
      return;
    }
    try {
      pendingContentIntentNotif.contentIntent.send();
      contentIntentSentThisSignal = true;
      Log.w(TAG, "CONTENT_INTENT_FALLBACK reason=" + reason
          + " pkg=" + contentIntentSignalPkg
          + " delayMs=" + (SystemClock.uptimeMillis() - contentIntentSignalT0));
      scheduleAcceptHuntPoll();
    } catch (Exception e) {
      Log.e(TAG, "CONTENT_INTENT_FAIL reason=" + reason, e);
    }
  }

  private void markAcceptFoundThisSignal() {
    acceptFoundThisSignal = true;
    cancelContentIntentFallback("accept-found");
  }

  /**
   * Clear orphan STRIKING/VERIFYING / burst locks that permanently block rides.
   * Without this, canStartRapidoBurst stays false forever after a hung VERIFY.
   */
  private void recoverIfRaceStuck(String reason) {
    long now = SystemClock.uptimeMillis();
    long age = racePhaseEnteredAtMs > 0 ? (now - racePhaseEnteredAtMs) : 0;
    boolean orphanVerify = racePhase == RacePhase.VERIFYING && !verifyingAccept;
    boolean orphanStrike = racePhase == RacePhase.STRIKING && !rapidoBurstLock && age > 600;
    boolean stuckStrike = racePhase == RacePhase.STRIKING && age > STUCK_STRIKING_MS;
    boolean stuckVerify = racePhase == RacePhase.VERIFYING && age > STUCK_VERIFYING_MS;
    boolean staleLock = rapidoBurstLock
        && (racePhase == RacePhase.IDLE || racePhase == RacePhase.ARMED || age > STUCK_STRIKING_MS);
    boolean stuckCooldown = racePhase == RacePhase.COOLDOWN
        && (age > COOLDOWN_HARD_CAP_MS || now >= ignoreRapidoUntilMs);
    if (!(orphanVerify || orphanStrike || stuckStrike || stuckVerify || staleLock
        || stuckCooldown)) {
      return;
    }
    Log.w(TAG, "RECOVER_STUCK " + reason
        + " phase=" + racePhase
        + " ageMs=" + age
        + " burstLock=" + rapidoBurstLock
        + " verifying=" + verifyingAccept);
    cancelVerify("recover/" + reason);
    finishRapidoMicroBurst("recover/" + reason);
    rapidoBurstLock = false;
    verifyingAccept = false;
    // Never leave a stuck COOLDOWN latch after recover
    if (stuckCooldown || now >= ignoreRapidoUntilMs) {
      acceptSuccessLatch = false;
      ignoreRapidoUntilMs = 0;
      rapidoCooldownUntilMs = 0;
    }
    if (racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING
        || racePhase == RacePhase.COOLDOWN) {
      if (isRaceArmed() || rapidoForeground) {
        setRacePhase(RacePhase.ARMED, "recover/" + reason);
      } else {
        setRacePhase(RacePhase.IDLE, "recover/" + reason);
      }
    }
    if (shouldRunAcceptHuntPoll()) {
      scheduleAcceptHuntPoll();
    }
  }

  /**
   * New ride ping: unlock hung STRIKE/VERIFY/COOLDOWN so huntAccept can run again.
   * Rapido never keeps the latch — the next overlay can appear in under 200ms.
   */
  private void prepareForNewRideSignal(String reason) {
    breakRapidoCooldownForNewRide();
    long now = SystemClock.uptimeMillis();
    raceEmitted = false;
    if (!(rapidoBurstLock || verifyingAccept
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING
        || racePhase == RacePhase.COOLDOWN
        || racePhase == RacePhase.ARMED)) {
      return;
    }
    long age = racePhaseEnteredAtMs > 0 ? (now - racePhaseEnteredAtMs) : 0;
    Log.w(TAG, "NEW_RIDE_UNLOCK " + reason
        + " phase=" + racePhase
        + " ageMs=" + age
        + " burstLock=" + rapidoBurstLock
        + " verifying=" + verifyingAccept);
    cancelVerify("new-ride/" + reason);
    finishRapidoMicroBurst("new-ride/" + reason);
    rapidoBurstLock = false;
    verifyingAccept = false;
    setRacePhase(RacePhase.ARMED, "new-ride/" + reason);
  }

  /**
   * True when every Rapido window on screen is float-icon sized (no ride card / sheet).
   * While Home/WhatsApp is FG and only the bubble is visible → do nothing.
   */
  private boolean onlyRapidoSurfaceIsBubble() {
    boolean sawRapido = false;
    boolean sawNonBubble = false;
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return false;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        AccessibilityNodeInfo root = null;
        try {
          root = w.getRoot();
          if (root == null) continue;
          if (!allowAsRapidoWindow(packageOf(root), lastPkg)) continue;
          sawRapido = true;
          w.getBoundsInScreen(scratchRect);
          if (isFloatingBubbleBounds(scratchRect)) {
            noteBubbleWindow(scratchRect);
          } else {
            sawNonBubble = true;
            break;
          }
        } catch (Exception ignored) {
        } finally {
          if (root != null) {
            try { root.recycle(); } catch (Exception ignored) {}
          }
        }
      }
    } catch (Exception ignored) {
    }
    return sawRapido && !sawNonBubble;
  }

  /**
   * Active app is not Rapido and the only Rapido surface is the float icon →
   * refuse hunt / gesture / contentIntent (prefer miss over reopen).
   * While race-armed / verifying, never idle — overlay Accept often paints after NLS.
   */
  private boolean shouldIdleForBubbleOnly() {
    // Late ride card: NLS arms before overlay exists; keep hunting through race phases
    if (isRaceArmed() || verifyingAccept || hasLiveRapidoOverlayCard()
        || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING
        || racePhase == RacePhase.COOLDOWN) {
      return false;
    }
    String active = null;
    try {
      active = resolveActivePackage();
    } catch (Exception ignored) {
    }
    if (active != null && isRapidoPackageName(active)) return false;
    // Non-ride-app FG (or unknown): if only bubble → idle
    return onlyRapidoSurfaceIsBubble();
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    sInstance = this;
    try {
      AutoClickerConfig.init(this);
      ShizukuInput.attach(this);
      detectAndApplyLowEndProfile();
      hydrateCachedAcceptFromDisk();
      registerConfigChangedReceiver();

      AccessibilityServiceInfo info = getServiceInfo();
      if (info != null) {
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.packageNames = null;
        setServiceInfo(info);
      }
      syncEngineForeground();
      // Clear any sticky latch from a previous soft-assume / crash mid-ignore
      acceptSuccessLatch = false;
      ignoreRapidoUntilMs = 0;
      rapidoCooldownUntilMs = 0;
      rapidoBurstLock = false;
      verifyingAccept = false;
      raceEmitted = false;
      clearPendingAcceptHistory();
      setRacePhase(RacePhase.IDLE, "service-connected");
      handler.removeCallbacks(raceWatchdogRunnable);
      handler.postDelayed(raceWatchdogRunnable, RACE_WATCHDOG_MS);
      EngineKeepAlive.ensureStarted(this);
      RecentsGuard.ensureStarted(this);
      Log.i(TAG, "ENGINE_STARTED enabled=" + AutoClickerConfig.isEnabled()
          + " nuclear=" + AutoClickerConfig.isNuclearMode()
          + " min=" + AutoClickerConfig.getMinPrice()
          + " maxPickup=" + AutoClickerConfig.getMaxPickup()
          + " shizuku=" + ShizukuInput.state(this)
          + " lowEnd=" + lowEndDevice
          + " heavyOem=" + heavyOem
          + " gestMs=" + rapidoGestureMs
          + " phase=IDLE");
    } catch (Throwable t) {
      Log.e(TAG, "ENGINE_START_CRASH " + t.getMessage(), t);
    }
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    Log.w(TAG, "onTaskRemoved — Recents swipe; engine stays armed");
    EngineKeepAlive.ensureStarted(this);
    RecentsGuard.ensureStarted(this);
    if (engineIsOn()) {
      startEngineForeground();
    }
    super.onTaskRemoved(rootIntent);
  }

  private void registerConfigChangedReceiver() {
    if (configReceiverRegistered) return;
    if (configChangedReceiver == null) {
      configChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
          if (intent == null) return;
          if (!AutoClickerModule.ACTION_CONFIG_CHANGED.equals(intent.getAction())) return;
          final boolean en = intent.getBooleanExtra("enabled", false);
          final boolean nuclear = intent.getBooleanExtra(
              "nuclear_mode", AutoClickerConfig.peekNuclearMode());
          final int minPrice = intent.getIntExtra("min_price", AutoClickerConfig.getMinPrice());
          final float maxPickup = intent.getFloatExtra("max_pickup", AutoClickerConfig.getMaxPickup());
          Log.i(TAG, "CONFIG_BROADCAST_RX enabled=" + en + " nuclear=" + nuclear
              + " min=" + minPrice + " maxPickup=" + maxPickup);
          handler.post(() -> {
            AutoClickerConfig.applyEnabledFromBroadcast(en);
            AutoClickerConfig.applyNuclearFromBroadcast(nuclear);
            AutoClickerConfig.applyFiltersFromBroadcast(minPrice, maxPickup);
            AutoClickerConfig.markBroadcastApplied();
            if (!en) {
              haltEngineMasterOff("config-broadcast-off");
              return;
            }
            resumeEngineMasterOn("config-broadcast");
          });
        }
      };
    }
    try {
      IntentFilter filter = new IntentFilter(AutoClickerModule.ACTION_CONFIG_CHANGED);
      if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(configChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        registerReceiver(configChangedReceiver, filter);
      }
      configReceiverRegistered = true;
      Log.i(TAG, "CONFIG_RECEIVER registered");
    } catch (Exception e) {
      Log.w(TAG, "CONFIG_RECEIVER register failed: " + e.getMessage());
    }
  }

  private void unregisterConfigChangedReceiver() {
    if (!configReceiverRegistered || configChangedReceiver == null) return;
    try {
      unregisterReceiver(configChangedReceiver);
    } catch (Exception ignored) {
    }
    configReceiverRegistered = false;
  }

  @Override
  public void onDestroy() {
    try {
      unregisterConfigChangedReceiver();
      stopAcceptHuntPoll("destroy");
      clearCachedAcceptPoint("destroy");
      cancelVerify("destroy");
      handler.removeCallbacks(rapidoMicroBurstRunnable);
      handler.removeCallbacks(rapidoRetryWindowEndRunnable);
      handler.removeCallbacks(nlsFollowRunnable);
      cancelPredictivePulses();
      handler.removeCallbacks(raceArmExpireRunnable);
      handler.removeCallbacks(olaUnlockStrikeRunnable);
      olaUnlockStrikeScheduled = false;
      handler.removeCallbacks(verifyAcceptRunnable);
      handler.removeCallbacks(historyConfirmRunnable);
      handler.removeCallbacks(raceWatchdogRunnable);
      handler.removeCallbacks(pendingRideFlushRunnable);
      cancelContentIntentFallback("destroy");
      pendingRideSignal = false;
      pendingRideNotification = null;
      finishRapidoMicroBurst("destroy");
      stopEngineForeground();
    } catch (Throwable t) {
      Log.e(TAG, "ENGINE_DESTROY_CRASH " + t.getMessage(), t);
    } finally {
      if (sInstance == this) sInstance = null;
      super.onDestroy();
    }
  }

  /**
   * Hunt while race-armed (NLS / overlay) OR driver app is foreground with a live card.
   * In-app Ola Accept often appears without a notification — must still hunt.
   * Standby after ACCEPT_OK covers along-route / Maps FG so ride 4+ is not dropped.
   * Never poll on bubble-only / other-app idle (that caused mid-screen spray).
   */
  private boolean shouldRunAcceptHuntPoll() {
    if (!engineIsOn()) return false;
    if (shouldIdleForBubbleOnly()) return false;
    if (isRaceActive() || hasLiveRapidoOverlayCard()) return true;
    if (isStandbyHunting() || hasNonBubbleRapidoWindow()) return true;
    if (!rapidoForeground) return false;
    // Ola + Rapido FG: label-only hunt (no geometry). Ola in-app cards have no NLS.
    return true;
  }

  /** True while racing a ride alert / mid-strike / verify. */
  private boolean isRaceActive() {
    return isRaceArmed()
        || verifyingAccept
        || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING;
  }

  /**
   * May find Accept while race-armed OR driver app FG / live overlay / standby.
   * Ola often shows the trip card in-app with no notification — hunt labeled
   * Accept while Ola is foreground. Geometry fallback still requires arm.
   */
  private boolean mayHuntAccept() {
    if (isRaceActive() || hasLiveRapidoOverlayCard()) return true;
    if (isStandbyHunting() || hasNonBubbleRapidoWindow()) return true;
    return rapidoForeground;
  }

  private void noteEmptyHunt(String source) {
    long now = SystemClock.uptimeMillis();
    if (emptyHuntStreakStartMs <= 0) emptyHuntStreakStartMs = now;
    consecutiveEmptyHunts++;
    lastEmptyHuntAtMs = now;
    maybeRefreshFrozenTree("empty/" + source);
  }

  private void noteHuntHit() {
    consecutiveEmptyHunts = 0;
    emptyHuntStreakStartMs = 0;
    lastEmptyHuntAtMs = 0;
    consecutiveGestureFails = 0;
  }

  private void maybeRefreshFrozenTree(String reason) {
    long now = SystemClock.uptimeMillis();
    if (emptyHuntStreakStartMs <= 0) return;
    boolean expectAccept = isRaceActive() || isStandbyHunting() || rapidoForeground
        || hasNonBubbleRapidoWindow();
    if (!expectAccept) return;
    if (now - emptyHuntStreakStartMs < FROZEN_TREE_EMPTY_MS) return;
    if (now - lastFrozenTreeRefreshMs < FROZEN_TREE_REFRESH_COOLDOWN_MS) return;
    refreshAccessibilityWindows(reason);
  }

  /**
   * Re-apply service info so ColorOS/MIUI/Funtouch rebuild window roots after
   * they stop delivering events (common after 2–3 accepts).
   */
  private void refreshAccessibilityWindows(String reason) {
    lastFrozenTreeRefreshMs = SystemClock.uptimeMillis();
    consecutiveEmptyHunts = 0;
    emptyHuntStreakStartMs = 0;
    try {
      AccessibilityServiceInfo info = getServiceInfo();
      if (info != null) {
        setServiceInfo(info);
      }
    } catch (Exception e) {
      Log.w(TAG, "A11Y_REFRESH_FAIL " + reason + " " + e.getMessage());
      return;
    }
    Log.w(TAG, "A11Y_REFRESH " + reason);
    if (shouldRunAcceptHuntPoll()) {
      scheduleAcceptHuntPollImmediate();
    }
  }

  /**
   * Overlay over Home: hunt while race-armed, OR sight a real Accept label unarmed.
   * Never geometry-guess — that caused random FG taps.
   */
  private void sheetOverlayProbe(long t0) {
    if (!engineIsOn()) return;
    if (isRapidoInteractionBlocked()) return;
    long now = SystemClock.uptimeMillis();
    if (now - lastOverlayProbeMs < 250L) return;
    lastOverlayProbeMs = now;

    if (!isRaceActive()) {
      if (probeUnarmedDriverOverlayAccept(t0)) return;
      return;
    }
    String pkg = lastPkg != null && isRapidoPackageName(lastPkg)
        ? lastPkg : AutoClickerConfig.PKG_OLA_DRIVER;
    huntAccept(pkg, t0, "OverlayProbe");
  }

  /**
   * Home / launcher FG: arm + strike only when a real Accept LABEL is visible.
   * No empty-text / geometry bar taps.
   */
  private boolean probeUnarmedDriverOverlayAccept(long t0) {
    String[] hints = new String[] {
        lastPkg,
        AutoClickerConfig.PKG_OLA_DRIVER,
        AutoClickerConfig.PKG_RAPIDO_CAPTAIN,
    };
    for (String hint : hints) {
      if (hint == null || !isRapidoPackageName(hint)) continue;
      HuntHit hit = findAcceptAcrossRapidoWindows(hint, false);
      if (hit == null || hit.node == null) continue;
      // HARD: must be a real Accept label (not a random bottom bar)
      if (!isRealAcceptLabel(nodeTextCs(hit.node)) && !viewIdLooksLikeAccept(hit.node)) {
        try { hit.node.recycle(); } catch (Exception ignored) {}
        continue;
      }
      String pkg = hit.pkg != null ? hit.pkg : hint;
      lastPkg = pkg;
      arm("overlay-sight/" + pkg);
      try {
        hit.node.getBoundsInScreen(scratchRect);
        if (!scratchRect.isEmpty()) {
          TapHighlightOverlay.showPoint(this, scratchRect.centerX(), scratchRect.centerY(), 28, 1200L);
          Log.w(TAG, "OVERLAY_ACCEPT_SIGHT @" + scratchRect.centerX() + "," + scratchRect.centerY()
              + " pkg=" + pkg + " label=" + nodeTextCs(hit.node));
        }
      } catch (Exception ignored) {
      }
      return fireHuntHit(hit.node, pkg, true, t0, "UnarmedOverlay");
    }
    return false;
  }

  private volatile long lastSheetCheckMs = 0L;
  private volatile boolean lastSheetCheckHit = false;

  /** True if any Rapido APPLICATION/overlay window is larger than the float icon. */
  private boolean hasNonBubbleRapidoWindow() {
    long now = SystemClock.uptimeMillis();
    if (now - lastSheetCheckMs < 200L) return lastSheetCheckHit;
    lastSheetCheckMs = now;
    boolean hit = false;
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows != null) {
        for (AccessibilityWindowInfo w : windows) {
          if (w == null) continue;
          AccessibilityNodeInfo root = null;
          try {
            root = w.getRoot();
            if (root == null) continue;
            String p = packageOf(root);
            if (!isRapidoPackageName(p)) continue;
            w.getBoundsInScreen(scratchRect2);
            if (!isFloatingBubbleBounds(scratchRect2)) {
              hit = true;
              break;
            }
          } catch (Exception ignored) {
          } finally {
            if (root != null) {
              try { root.recycle(); } catch (Exception ignored) {}
            }
          }
        }
      }
    } catch (Exception ignored) {
    }
    lastSheetCheckHit = hit;
    return hit;
  }

  /** True during an armed/striking/verifying Accept race (from a ride alert). */
  private boolean isAcceptRaceHot() {
    return isRaceArmed()
        || verifyingAccept
        || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING;
  }

  private void scheduleAcceptHuntPoll() {
    if (acceptHuntPollScheduled) return;
    if (!shouldRunAcceptHuntPoll()) return;
    acceptHuntPollScheduled = true;
    // Race hot path: near-FG speed. Standby / Ola FG idle uses a slower label poll.
    long delay;
    if (isRaceActive()
        || (rapidoForeground && !AutoClickerConfig.isOlaPackage(lastPkg))) {
      delay = acceptHuntPollMs;
    } else {
      delay = STANDBY_HUNT_POLL_MS;
    }
    handler.postDelayed(acceptHuntPollRunnable, delay);
  }

  /** Next hunt ASAP on the main queue (used right after NLS miss). */
  private void scheduleAcceptHuntPollImmediate() {
    if (!shouldRunAcceptHuntPoll()) return;
    handler.removeCallbacks(acceptHuntPollRunnable);
    acceptHuntPollScheduled = true;
    handler.postAtFrontOfQueue(acceptHuntPollRunnable);
  }

  private void stopAcceptHuntPoll(String reason) {
    handler.removeCallbacks(acceptHuntPollRunnable);
    acceptHuntPollScheduled = false;
  }

  /**
   * True when a new Rapido Accept micro-burst may start.
   * Blocked in COOLDOWN / STRIKING; VERIFYING (UI) owns restrikes.
   */
  private boolean canStartRapidoBurst() {
    if (isRapidoInteractionBlocked()) return false;
    if (racePhase == RacePhase.COOLDOWN) return false;
    if (rapidoBurstLock || racePhase == RacePhase.STRIKING) return false;
    if (racePhase == RacePhase.VERIFYING && verifyingAccept && !verifyFromPendingIntent) {
      return false;
    }
    long now = SystemClock.uptimeMillis();
    if (now < rapidoCooldownUntilMs) return false;
    if (now < rapidoRetryUntilMs) {
      return now - lastRapidoAttemptMs >= RAPIDO_BURST_GAP_MS;
    }
    return true;
  }

  private boolean isRapidoPackageName(String pkg) {
    return AutoClickerConfig.isRapidoPackage(pkg);
  }

  /** Live overlay card bounds from a Rapido window — not stale armed/sticky alone. */
  private boolean hasOverlayCardBounds() {
    return overlayCardBoundsValid && !overlayCardBoundsRect.isEmpty();
  }

  private boolean hasLiveRapidoOverlayCard() {
    return hasOverlayCardBounds()
        && SystemClock.uptimeMillis() <= overlayLiveUntilMs;
  }

  /**
   * Rapido node check. Android 15 / HyperOS often leaves getPackageName() null —
   * allow when lastPkg / active window / live overlay establishes Rapido context.
   */
  private boolean nodeIsRapido(AccessibilityNodeInfo node) {
    if (node == null) return false;
    String p = packageOf(node);
    if (p != null) return isRapidoPackageName(p);
    // Null packageName is common on Android 15 / HyperOS Accept overlays
    if (isAcceptRaceHot()) return true;
    return allowAsRapidoWindow(null, lastPkg);
  }

  private boolean pointInsideOverlayCard(int x, int y) {
    return hasOverlayCardBounds() && overlayCardBoundsRect.contains(x, y);
  }

  /**
   * Absolute-coord tap gate: ONLY a freshly measured Accept LABEL center.
   * Never allow Captain-FG alone — that re-enabled mid-screen spam after Accept left.
   * Sticky overlay + exact overlay tap point required for every gesture.
   */
  private boolean canGestureAt(int x, int y) {
    if (x <= 0 || y <= 0) return false;
    if (isRapidoInteractionBlocked()) return false;
    if (shouldIdleForBubbleOnly()) return false;

    if (!isRaceActive() && !verifyingAccept) return false;

    // Ridio FG: never gesture unless a live Rapido Accept overlay mark exists.
    // Do NOT allow bare race-armed / verifying with stale mid-screen coords on our UI.
    if (isSelfAppForeground()) {
      if (!hasLiveRapidoOverlayCard()) return false;
      if (overlayTapX > 0 && overlayTapY > 0) {
        if (Math.abs(x - overlayTapX) > 48 || Math.abs(y - overlayTapY) > 48) return false;
      } else {
        return false;
      }
    }

    // ONLY exact measured Accept centers — never random / mid-screen spray
    boolean exactAccept =
        (verifyCx > 0 && verifyCy > 0 && x == verifyCx && y == verifyCy)
        || (rapidoBurstX > 0 && rapidoBurstY > 0 && x == rapidoBurstX && y == rapidoBurstY)
        || (cachedAcceptValid && x == cachedAcceptX && y == cachedAcceptY);
    if (!exactAccept) return false;

    // Must match the last live Accept LABEL mark (tight)
    if (!hasLiveRapidoOverlayCard()) {
      // Strike-0 / VERIFY only — never the whole armed TTL (that caused mid-screen spam)
      if (racePhase != RacePhase.STRIKING
          && racePhase != RacePhase.VERIFYING
          && !verifyingAccept) {
        return false;
      }
      return true;
    }
    if (overlayTapX > 0 && overlayTapY > 0) {
      if (Math.abs(x - overlayTapX) > 48 || Math.abs(y - overlayTapY) > 48) return false;
    }
    if (hasOverlayCardBounds() && !overlayCardBoundsRect.contains(x, y)) return false;
    return true;
  }

  /** Drop stale gesture coords so mid-screen taps cannot continue after Accept leaves. */
  private void clearStaleAcceptTapPoints(String reason) {
    verifyCx = 0;
    verifyCy = 0;
    rapidoBurstX = 0;
    rapidoBurstY = 0;
    clearBlindSprayTargets(reason);
    overlayCardBoundsValid = false;
    overlayCardBoundsRect.setEmpty();
    overlayLiveUntilMs = 0;
  }

  private void cacheAcceptPoint(String pkg, int x, int y) {
    if (x <= 0 || y <= 0) return;
    String usePkg = pkg;
    if (usePkg == null || !isRapidoPackageName(usePkg)) {
      usePkg = lastPkg != null && isRapidoPackageName(lastPkg)
          ? lastPkg : AutoClickerConfig.PKG_RAPIDO_CAPTAIN;
    }
    // Never cache bubble / float-icon centers — reopen loop on some OEMs
    if (pointInsideKnownBubble(x, y) || pointInsideRapidoBubbleWindow(x, y)) return;
    cachedAcceptX = x;
    cachedAcceptY = y;
    cachedAcceptPkg = usePkg;
    cachedAcceptValid = true;
    cachedAcceptAtMs = SystemClock.uptimeMillis();
    AutoClickerConfig.cacheTapPoint(usePkg, x, y);
  }

  /**
   * Drop in-memory Accept cache. CACHE_STRIKE also requires a fresh live Accept.
   */
  private void clearCachedAcceptPoint(String reason) {
    cachedAcceptValid = false;
    cachedAcceptX = 0;
    cachedAcceptY = 0;
    cachedAcceptPkg = null;
    cachedAcceptAtMs = 0;
    boolean poison = reason != null && (reason.contains("bubble") || "destroy".equals(reason));
    if ("destroy".equals(reason)) return;
    if (poison && lastPkg != null) {
      AutoClickerConfig.clearCachedTapPoints(lastPkg);
    }
  }

  private void markOverlayLive(Rect bounds, int tapX, int tapY) {
    if (bounds == null || bounds.isEmpty()) return;
    // Wide/flat Accept CTAs must mark sticky — bubble geometry was blocking gestures
    if (isFloatingBubbleBounds(bounds) && !isRideCardSizedBounds(bounds)) {
      // Tiny square chat-head only
      if (isBubbleLikeClickTarget(bounds)) return;
    }
    overlayCardBoundsRect.set(bounds);
    overlayCardBoundsValid = true;
    overlayTapX = tapX;
    overlayTapY = tapY;
    overlayLiveUntilMs = SystemClock.uptimeMillis() + OVERLAY_STICKY_MS;
    overlayStickyUntilMs = overlayLiveUntilMs;
  }

  private void clearBlindSprayTargets(String reason) {
    overlayTapX = 0;
    overlayTapY = 0;
    overlayLiveUntilMs = 0;
  }

  /**
   * After VERIFY confirms Accept gone: enter short COOLDOWN, then IDLE.
   * Fresh NLS after COOLDOWN can arm again even while still on-trip.
   */
  private void disarmAfterAcceptSuccess(String reason) {
    long now = SystemClock.uptimeMillis();
    cancelVerify("disarm/" + reason);
    cancelContentIntentFallback("accept-ok");
    handler.removeCallbacks(olaUnlockStrikeRunnable);
    olaUnlockStrikeScheduled = false;
    acceptSuccessLatch = true;
    ignoreRapidoUntilMs = now + RACE_COOLDOWN_MS;
    lastAcceptOkAtMs = now;
    extendStandbyHunt(reason);
    raceArmedUntilMs = 0;
    raceArmedFromMs = 0;
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.removeCallbacks(nlsFollowRunnable);
    nlsFollowActive = false;
    // KEEP last Accept x,y — wiping them made the next 0ms ping miss
    finishRapidoMicroBurst("accept-ok");
    rapidoRetryUntilMs = 0;
    rapidoCooldownUntilMs = now + RACE_COOLDOWN_MS;
    setRacePhase(RacePhase.COOLDOWN, reason);
    handler.removeCallbacks(historyConfirmRunnable);
    handler.post(() -> flushConfirmedAcceptHistory(reason));
    handler.removeCallbacks(pendingRideFlushRunnable);
    handler.postDelayed(pendingRideFlushRunnable, RACE_COOLDOWN_MS + 20L);
    if (shouldRunAcceptHuntPoll() || rapidoForeground) {
      scheduleAcceptHuntPollImmediate();
    }
    Log.i(TAG, "ACCEPT_OK resume-in=" + (RACE_COOLDOWN_MS + 20L) + "ms reason=" + reason);
  }

  /** Clear VERIFYING without latching success (miss / abort). Return to ARMED if still armed. */
  private void cancelVerify(String reason) {
    verifyingAccept = false;
    verifyFromPendingIntent = false;
    verifyUntilMs = 0;
    verifyRestrikeCount = 0;
    handler.removeCallbacks(verifyAcceptRunnable);
    // disarmAfterAcceptSuccess clears verify then sets COOLDOWN — don't bounce to ARMED
    if (reason != null && reason.startsWith("disarm/")) return;
    if (racePhase == RacePhase.VERIFYING || racePhase == RacePhase.STRIKING) {
      if (isRaceArmed() || rapidoForeground) {
        setRacePhase(RacePhase.ARMED, "cancel-verify/" + reason);
      } else {
        setRacePhase(RacePhase.IDLE, "cancel-verify/" + reason);
      }
    }
  }

  /**
   * Enter VERIFYING after a strike or PendingIntent — do not COOLDOWN yet.
   * Success requires Accept gone. PendingIntent never latches alone without window expiry.
   */
  private void beginVerify(
      String pkg, String tag, long findAt, int cx, int cy, boolean fromPendingIntent) {
    verifyingAccept = true;
    verifyFromPendingIntent = fromPendingIntent;
    verifyPkg = pkg != null ? pkg : lastPkg;
    verifyTag = tag;
    verifyFindAt = findAt > 0 ? findAt : SystemClock.uptimeMillis();
    verifyCx = cx;
    verifyCy = cy;
    verifyRestrikeCount = 0;
    // UI strike already measured Accept under the finger
    acceptSeenDuringVerify = !fromPendingIntent && (pendingHistoryValid || (cx > 0 && cy > 0));
    verifyUntilMs = SystemClock.uptimeMillis() + VERIFY_WINDOW_MS;
    setRacePhase(RacePhase.VERIFYING, fromPendingIntent ? "verify-action" : "verify-ui");
    refreshRaceArm("begin-verify");
    handler.removeCallbacks(verifyAcceptRunnable);
    handler.postDelayed(verifyAcceptRunnable, VERIFY_POLL_MS);
  }

  /** Clear the post-Accept latch when a fresh ride signal arrives — never during hard ignore. */
  private void clearAcceptSuccessLatch(String reason) {
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    acceptSuccessLatch = false;
  }

  /**
   * True if any Rapido window shows on-trip / nav chrome (End Ride, Navigate, etc.).
   * Context only — does NOT mean "stop hunting Accept".
   */
  private boolean rapidoLooksOnTripAnywhere() {
    try {
      AccessibilityNodeInfo active = getRootInActiveWindow();
      if (active != null) {
        try {
          if (allowAsRapidoWindow(packageOf(active), lastPkg) && rootHasOnTripChrome(active)) {
            return true;
          }
        } finally {
          active.recycle();
        }
      }
    } catch (Exception ignored) {
    }
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return false;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        AccessibilityNodeInfo root = null;
        try {
          root = w.getRoot();
          if (root == null) continue;
          if (!allowAsRapidoWindow(packageOf(root), lastPkg)) continue;
          if (rootHasOnTripChrome(root)) return true;
        } finally {
          if (root != null) {
            try { root.recycle(); } catch (Exception ignored) {}
          }
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /** Find Accept on active root or any Rapido window. Caller owns the node. */
  private AccessibilityNodeInfo findAcceptNodeAnywhere() {
    AccessibilityNodeInfo hit = null;
    try {
      AccessibilityNodeInfo active = getRootInActiveWindow();
      if (active != null) {
        try {
          if (allowAsRapidoWindow(packageOf(active), lastPkg)
              && !isFloatingBubbleNode(active)) {
            hit = findAcceptLabelInRoot(active);
          }
        } finally {
          active.recycle();
        }
      }
    } catch (Exception ignored) {
    }
    if (hit != null) return hit;
    HuntHit multi = findAcceptAcrossRapidoWindows(lastPkg, rapidoForeground);
    return multi != null ? multi.node : null;
  }

  /**
   * Note on-trip/nav chrome on this tree. Never a reason to skip Accept hunt.
   * @return true if chrome labels present
   */
  private boolean noteOnTripChrome(AccessibilityNodeInfo root) {
    if (rootHasOnTripChrome(root)) {
      if (!driverOnTripChrome) {
        Log.i(TAG, "ON_TRIP_CHROME detected — still hunting Accept");
      }
      driverOnTripChrome = true;
      return true;
    }
    return false;
  }

  /** True if this tree shows nav / End Ride / On Trip chrome. */
  private boolean rootHasOnTripChrome(AccessibilityNodeInfo root) {
    if (root == null) return false;
    for (String label : ON_TRIP_CHROME_LABELS) {
      if (treeHasLabel(root, label, true)) return true;
    }
    return false;
  }

  /** @deprecated Use {@link #rootHasOnTripChrome} — name kept for call-site clarity during migrate. */
  @Deprecated
  private boolean rootLooksOnTrip(AccessibilityNodeInfo root) {
    return rootHasOnTripChrome(root);
  }

  private boolean treeHasLabel(AccessibilityNodeInfo root, String label, boolean onTripSemantics) {
    List<AccessibilityNodeInfo> nodes;
    try {
      nodes = root.findAccessibilityNodeInfosByText(label);
    } catch (Exception e) {
      return false;
    }
    if (nodes == null || nodes.isEmpty()) return false;
    boolean hit = false;
    for (AccessibilityNodeInfo n : nodes) {
      if (n == null) continue;
      CharSequence t = nodeTextCs(n);
      if (t != null) {
        if (onTripSemantics) {
          if (containsIgnoreCase(t, "trip started")
              || containsIgnoreCase(t, "end ride")
              || containsIgnoreCase(t, "complete ride")
              || containsIgnoreCase(t, "go to pickup")
              || containsIgnoreCase(t, "navigate to pickup")
              || containsIgnoreCase(t, "on trip")
              || equalsIgnoreCaseTrim(t, "navigate")) {
            hit = true;
          }
        } else if (containsIgnoreCase(t, "accepted")) {
          hit = true;
        }
      }
      try { n.recycle(); } catch (Exception ignored) {}
    }
    return hit;
  }

  private boolean dumpLooksOnTrip(String dump) {
    if (dump == null || dump.isEmpty()) return false;
    String lower = dump.toLowerCase(Locale.US);
    return lower.contains("trip started")
        || lower.contains("on trip")
        || lower.contains("end ride")
        || lower.contains("complete ride")
        || lower.contains("go to pickup")
        || lower.contains("navigate to");
  }

  /** True if a real Accept label is still on a non-bubble Rapido surface. */
  private boolean acceptStillVisibleAnywhere() {
    AccessibilityNodeInfo hit = findAcceptNodeAnywhere();
    if (hit == null) return false;
    try { hit.recycle(); } catch (Exception ignored) {}
    return true;
  }

  /** ACTION_CLICK on Rapido nodes (null packageName OK when Rapido context is set). */
  private boolean performRapidoClick(AccessibilityNodeInfo node) {
    if (node == null || !nodeIsRapido(node)) return false;
    if (isRapidoInteractionBlocked()) return false;
    // Before every ACTION_CLICK: node tap-target OR containing window = float icon → refuse
    if (isFloatingBubbleNode(node) && !isAcceptRaceHot() && !rapidoForeground) return false;
    try {
      node.getBoundsInScreen(scratchRect);
      if (isBubbleLikeClickTarget(scratchRect) && !isAcceptRaceHot() && !rapidoForeground) return false;
    } catch (Exception ignored) {
    }
    // While user is on WhatsApp/etc, only click real Accept (not float icon).
    // When armed/verifying, allow even if window type looks like a full sheet —
    // HyperOS ride alerts are often APPLICATION windows, not TYPE_SYSTEM.
    if (!rapidoForeground) {
      String active = null;
      try {
        active = resolveActivePackage();
      } catch (Exception ignored) {
      }
      if (active != null && !isRapidoPackageName(active)) {
        if (shouldIdleForBubbleOnly()) return false;
        boolean allowArmed = isRaceArmed()
            || verifyingAccept
            || hasLiveRapidoOverlayCard()
            || racePhase == RacePhase.ARMED
            || racePhase == RacePhase.STRIKING
            || racePhase == RacePhase.VERIFYING;
        try {
          AccessibilityWindowInfo w = node.getWindow();
          if (w != null) {
            w.getBoundsInScreen(scratchRect2);
            // Armed race: compact ride overlays are often misclassified as bubbles
            if (isFloatingBubbleBounds(scratchRect2) && !allowArmed) return false;
            if (!allowArmed && !isLikelyOverlayWindow(w)) return false;
          } else if (!allowArmed) {
            return false;
          }
        } catch (Exception e) {
          if (!allowArmed) return false;
        }
      }
    }
    try {
      return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isSelfAppForeground() {
    long now = SystemClock.uptimeMillis();
    if (now - selfFgCachedAtMs < SELF_FG_CACHE_MS) {
      return selfFgCached;
    }
    boolean fg = false;
    try {
      String p = resolveActivePackage();
      fg = p != null && p.equals(getPackageName());
    } catch (Exception ignored) {
    }
    selfFgCached = fg;
    selfFgCachedAtMs = now;
    return fg;
  }

  /** Cheap cache update from event package — avoids root fetch on every event. */
  private void noteEventPackage(String pkg) {
    if (pkg == null || pkg.isEmpty()) return;
    selfFgCached = pkg.equals(getPackageName());
    selfFgCachedAtMs = SystemClock.uptimeMillis();
  }

  private boolean isRaceArmed() {
    return SystemClock.uptimeMillis() <= raceArmedUntilMs;
  }

  /**
   * System / cover UIs that sit over Captain but are NOT a real app switch.
   * Call screen, lock/keyguard, shade, dialer — keep hunting when race-armed.
   */
  private static boolean isTransientChromePackage(String pkg) {
    if (pkg == null || pkg.isEmpty()) return true;
    String p = pkg.toLowerCase(Locale.US);
    if (p.contains("systemui")) return true;
    if (p.contains("keyguard") || p.contains("lockscreen") || p.contains("lock.screen")) {
      return true;
    }
    // In-call / dialer (AOSP, Google, Samsung, Oppo/Realme, Xiaomi, Vivo, …)
    if (p.contains("incallui") || p.contains("incall") || p.contains("in_call")) return true;
    if (p.contains("dialer") || p.contains("telecom")) return true;
    if (p.equals("com.android.phone") || p.startsWith("com.android.phone")) return true;
    if (p.contains("com.samsung.android.incallui")) return true;
    if (p.contains("com.google.android.dialer")) return true;
    if (p.contains("com.android.server.telecom")) return true;
    if ((p.contains("oplus") || p.contains("coloros") || p.contains("heytap")
        || p.contains("realme") || p.contains("oneplus"))
        && (p.contains("call") || p.contains("phone") || p.contains("dial"))) {
      return true;
    }
    if ((p.contains("miui") || p.contains("xiaomi") || p.contains("com.android.contacts"))
        && (p.contains("call") || p.contains("incall"))) {
      return true;
    }
    if (p.contains("permissioncontroller") || p.contains("packageinstaller")) return true;
    if (p.contains("screenshot") || p.contains("globalactions")) return true;
    if (p.contains("com.oplus.stdsp")) return true;
    return false;
  }

  /** Wake display briefly so lock/doze devices can show/tap Accept overlays. */
  private void wakeScreenForRace() {
    try {
      PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
      if (pm == null) return;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH && pm.isInteractive()) {
        return;
      }
      @SuppressWarnings("deprecation")
      PowerManager.WakeLock wl = pm.newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
          "superridex:race");
      wl.acquire(2500L);
      handler.postDelayed(() -> {
        try {
          if (wl.isHeld()) wl.release();
        } catch (Exception ignored) {
        }
      }, 2600L);
    } catch (Exception ignored) {
    }
  }

  /**
   * Update Rapido-FG flag from the real active window — never claim FG just because
   * a Rapido overlay/bubble fired an a11y event while the user is on WhatsApp.
   */
  private void syncRapidoForegroundFromActive(String reason) {
    String active = null;
    try {
      active = resolveActivePackage();
    } catch (Exception ignored) {
    }
    if (active != null && isRapidoPackageName(active)) {
      setRapidoForeground(true, reason);
      return;
    }
    // Heads-up / notification shade: keep sticky FG + keep hunting
    if (active != null && isTransientChromePackage(active)) {
      if (rapidoForeground || isRaceArmed()
          || racePhase == RacePhase.ARMED
          || racePhase == RacePhase.VERIFYING
          || racePhase == RacePhase.STRIKING) {
        scheduleAcceptHuntPoll();
        Log.i(TAG, "FG_RAPIDO sticky (ignore-" + active + ") armed=" + isRaceArmed());
      }
      return;
    }
    if (active != null) {
      setRapidoForeground(false, reason + "/" + active);
    }
  }

  private void setRapidoForeground(boolean fg, String reason) {
    if (rapidoForeground == fg) {
      if (fg || isRaceActive() || isStandbyHunting() || hasNonBubbleRapidoWindow()) {
        scheduleAcceptHuntPoll();
      } else {
        stopAcceptHuntPoll(reason + "/already-bg");
        clearStaleAcceptTapPoints(reason + "/already-bg");
        clearCachedAcceptPoint(reason + "/already-bg");
      }
      return;
    }
    rapidoForeground = fg;
    if (fg) {
      cancelContentIntentFallback("rapido-fg");
      Log.i(TAG, "FG_RAPIDO true (" + reason + ") onTripChrome=" + driverOnTripChrome);
      // Captain FG: keep hunting Accept even without a prior NLS arm
      scheduleAcceptHuntPoll();
    } else {
      Log.i(TAG, "FG_RAPIDO false (" + reason + ") armed=" + isRaceArmed()
          + " standby=" + isStandbyHunting());
      // Maps / nav after accept: do NOT kill hunt — along-route cards still appear
      if (isRaceActive() || isStandbyHunting() || hasNonBubbleRapidoWindow()) {
        scheduleAcceptHuntPoll();
      } else {
        stopAcceptHuntPoll(reason);
        clearStaleAcceptTapPoints(reason);
        clearCachedAcceptPoint(reason);
      }
    }
  }

  private void setRideOverlayActive(boolean active, String reason) {
    if (rideOverlayActive == active) return;
    rideOverlayActive = active;
    if (!active && !rapidoForeground) {
      overlayLiveUntilMs = 0;
      overlayCardBoundsValid = false;
      overlayCardBoundsRect.setEmpty();
    }
  }

  private String resolveActivePackage() {
    try {
      AccessibilityNodeInfo root = getRootInActiveWindow();
      if (root != null) {
        try {
          CharSequence p = root.getPackageName();
          return p != null ? p.toString() : null;
        } finally {
          root.recycle();
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  /** Floating ride card / system overlay — not fullscreen Captain and not the bubble icon. */
  private boolean isLikelyOverlayWindow(AccessibilityWindowInfo w) {
    if (w == null) return false;
    int type = w.getType();
    if (type == AccessibilityWindowInfo.TYPE_SYSTEM) {
      // Still reject icon-sized system windows
      try {
        w.getBoundsInScreen(scratchRect);
        if (isFloatingBubbleBounds(scratchRect)) {
          noteBubbleWindow(scratchRect);
          return false;
        }
      } catch (Exception ignored) {
      }
      return true;
    }
    try {
      w.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()) return false;
      if (isFloatingBubbleBounds(scratchRect)) {
        noteBubbleWindow(scratchRect);
        return false;
      }
      ensureScreenMetrics();
      if (screenArea <= 0) return false;
      float area = (float) scratchRect.width() * (float) scratchRect.height();
      if (area <= 0) return false;
      boolean notFullscreen = area < screenArea * 0.88f
          && scratchRect.height() < screenH * 0.94f;
      return notFullscreen;
    } catch (Exception e) {
      return false;
    }
  }

  private void arm(String reason) {
    // Do not re-arm during COOLDOWN
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    // Mid-strike / VERIFY: keep arm alive — do NOT blind-restrike (VERIFY owns restrikes)
    if (verifyingAccept
        || racePhase == RacePhase.STRIKING
        || racePhase == RacePhase.VERIFYING) {
      refreshRaceArm("arm-keep-strike/" + reason);
      scheduleAcceptHuntPoll();
      return;
    }
    // Stale burst lock was blocking NlsHunt while phase=ARMED
    if (rapidoBurstLock) {
      finishRapidoMicroBurst("re-arm");
    }
    acceptSuccessLatch = false;
    raceEmitted = false;
    clearPendingAcceptHistory();
    // Keep lastRideFare from this NLS — wiping forced a second parse before strike-0
    long now = SystemClock.uptimeMillis();
    boolean freshArm = (raceArmedFromMs == 0 || now > raceArmedUntilMs);
    if (freshArm) {
      raceArmedFromMs = now;
      olaAcceptFirstSeenMs = 0L;
      olaSawCountdownThisRace = false;
      olaShizukuTapsThisRace = 0;
      olaUnlockDeferCount = 0;
      olaUnlockFireAtMs = 0L;
      handler.removeCallbacks(olaUnlockStrikeRunnable);
      olaUnlockStrikeScheduled = false;
    }
    // Ola needs arm through alert+5s unlock; Rapido keeps normal TTL
    long armFor = raceArmMs;
    if (lastPkg != null && AutoClickerConfig.isOlaPackage(lastPkg)) {
      armFor = Math.max(armFor, OLA_ARM_MIN_AFTER_ALERT_MS);
    }
    raceArmedUntilMs = Math.max(raceArmedUntilMs, raceArmedFromMs + armFor);
    raceArmedUntilMs = Math.max(raceArmedUntilMs, now + armFor);
    setRacePhase(RacePhase.ARMED, reason);
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.postDelayed(raceArmExpireRunnable, Math.max(30, raceArmedUntilMs - now + 30));
    scheduleAcceptHuntPoll();
    // If this signal is Ola (or unknown until Accept), schedule unlock fire at +5s
    scheduleOlaUnlockStrikeIfNeeded(lastPkg);
  }

  /** Post Ola strike when the on-screen slider should finish (not NLS time). */
  private void scheduleOlaUnlockStrikeIfNeeded(String pkg) {
    scheduleOlaUnlockStrikeIfNeeded(pkg, -1);
  }

  private void scheduleOlaUnlockStrikeIfNeeded(String pkg, int countdownLeft) {
    if (pkg == null || !AutoClickerConfig.isOlaPackage(pkg)) return;
    if (olaShizukuTapsThisRace >= OLA_MAX_SHIZUKU_TAPS) return;
    long now = SystemClock.uptimeMillis();
    long start = olaAcceptFirstSeenMs > 0 ? olaAcceptFirstSeenMs : raceArmedFromMs;
    if (start <= 0) return;

    long candidate;
    if (countdownLeft > 0) {
      candidate = now + countdownLeft * 1000L + OLA_UNLOCK_GRACE_MS;
    } else if (olaSawCountdownThisRace) {
      // Numbers just cleared — Accept is revealed now
      candidate = now + 80L;
    } else {
      candidate = start + OLA_UNLOCK_FROM_ALERT_MS + OLA_UNLOCK_GRACE_MS;
    }
    if (candidate < now) candidate = now;
    // Do not push the fire later if a closer time is already scheduled
    if (olaUnlockFireAtMs > 0L && candidate > olaUnlockFireAtMs + 40L
        && olaUnlockStrikeScheduled) {
      return;
    }
    olaUnlockFireAtMs = candidate;
    long delay = Math.max(0L, candidate - now);
    olaUnlockStrikeScheduled = true;
    handler.removeCallbacks(olaUnlockStrikeRunnable);
    handler.postDelayed(olaUnlockStrikeRunnable, delay);
    long needUntil = Math.max(raceArmedFromMs + OLA_ARM_MIN_AFTER_ALERT_MS, candidate + 1500L);
    if (raceArmedUntilMs < needUntil) {
      raceArmedUntilMs = needUntil;
      handler.removeCallbacks(raceArmExpireRunnable);
      handler.postDelayed(raceArmExpireRunnable, Math.max(30, raceArmedUntilMs - now + 30));
    }
    Log.i(TAG, "OLA_UNLOCK_SCHEDULED inMs=" + delay
        + " countdown=" + countdownLeft
        + " firstSeenAge=" + (olaAcceptFirstSeenMs > 0 ? (now - olaAcceptFirstSeenMs) : -1)
        + " alertAge=" + (raceArmedFromMs > 0 ? (now - raceArmedFromMs) : -1));
  }

  /**
   * Extend arm when Accept is sighted — late overlays stay hunt-able without
   * unbounded WhatsApp polling (capped from first signal).
   */
  private void refreshRaceArm(String reason) {
    if (SystemClock.uptimeMillis() < ignoreRapidoUntilMs) return;
    long now = SystemClock.uptimeMillis();
    if (raceArmedFromMs == 0) {
      raceArmedFromMs = now;
    }
    long maxUntil = raceArmedFromMs + RACE_ARM_MAX_FROM_SIGNAL_MS;
    long extended = Math.min(now + RACE_ARM_EXTEND_MS, maxUntil);
    if (extended <= raceArmedUntilMs) {
      // Still schedule poll if armed
      if (isRaceArmed()) scheduleAcceptHuntPoll();
      return;
    }
    raceArmedUntilMs = extended;
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.postDelayed(raceArmExpireRunnable, Math.max(30, raceArmedUntilMs - now + 30));
    scheduleAcceptHuntPoll();
  }

  /**
   * NLS / a11y notification: Rapido Accept PendingIntent first, then predictive
   * channels, before any main-queue tree work. Never contentIntent-spam Captain
   * open (bubble / Home reopen loop). PendingIntent enters VERIFY only after the
   * foreground-independent overlay hunt gets its immediate chance.
   */
  public static void onRideSignal(
      String packageName,
      Notification notification,
      String notifText,
      long tReceive,
      String source
  ) {
    final AutoClickerService svc = sInstance;
    if (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled()) return;
    if (packageName == null || !AutoClickerConfig.isPackageMonitored(packageName)) return;
    if (isSpamText(notifText)) {
      Log.w(TAG, "RIDE_SIGNAL_DROP spam pkg=" + packageName + " src=" + source);
      return;
    }
    // CRITICAL: RideAlertListener already decided ride (opaque / driver cue).
    // Re-running isRideAlert here silently dropped Rapido/Ola arms (NLS_POST with no RIDE_SIGNAL).
    boolean rideOk = isRideAlert(notifText, notification);
    boolean fromNls = source != null && source.startsWith("NLS");
    boolean targetPkg = AutoClickerConfig.isTargetPackage(packageName);
    if (!rideOk && !fromNls && !targetPkg) {
      Log.w(TAG, "RIDE_SIGNAL_DROP not-ride pkg=" + packageName + " src=" + source);
      return;
    }
    if (!rideOk) {
      Log.w(TAG, "RIDE_SIGNAL_TRUST src=" + source
          + " pkg=" + packageName
          + " nls=" + fromNls
          + " target=" + targetPkg
          + " — isRideAlert=false but arming anyway");
    }

    if (svc == null) {
      Log.w(TAG, "RIDE_SIGNAL_DROP no-service pkg=" + packageName);
      return;
    }

    // Stamp ride signal even if we must defer arm (back-to-back)
    svc.lastPkg = packageName;

    boolean captain = AutoClickerConfig.isCaptainRapidoPackage(packageName);
    boolean filtersOk = notifPassesMinPrice(notifText) && notifPassesMaxPickup(notifText);
    // Rapido + fare/pickup filters: skip notif Accept so the card can be parsed.
    boolean rapidoFiltersOn = captain
        && (AutoClickerConfig.getMinPrice() > 0 || AutoClickerConfig.getMaxPickup() > 0f);
    boolean actionOk = false;

    // Rapido: never defer. Fire the notification action before gesture/Shizuku
    // setup so no cache or binder work delays the fastest available channel.
    if (captain) {
      svc.breakRapidoCooldownForNewRide();
      if (!rapidoFiltersOn) {
        actionOk = tryFireAcceptPendingIntent(notification, notifText, tReceive, source);
      }
      if (filtersOk) {
        svc.firePredictiveRapidoTap(packageName, tReceive, source);
      }
    } else if (svc.isRapidoInteractionBlocked()) {
      svc.deferRideSignal(packageName, notification, notifText, tReceive, source);
      return;
    } else {
      actionOk = tryFireAcceptPendingIntent(notification, notifText, tReceive, source);
    }

    final boolean acceptActionOk = actionOk;

    // Always arm + hunt after NLS — even if Accept PendingIntent fired.
    Runnable race = () -> {
      if (!svc.engineIsOn()) return;
      if (captain) {
        svc.breakRapidoCooldownForNewRide();
      } else if (SystemClock.uptimeMillis() < svc.ignoreRapidoUntilMs) {
        svc.deferRideSignal(packageName, notification, notifText, tReceive, source);
        return;
      }
      svc.prepareForNewRideSignal(source);
      svc.recoverIfRaceStuck("ride-signal");
      svc.acceptSuccessLatch = false;
      svc.raceEmitted = false;
      svc.lastRideFare = 0;
      svc.lastRidePickupKm = 0f;
      svc.olaAcceptFirstSeenMs = 0L;
      svc.olaSawCountdownThisRace = false;
      svc.handler.removeCallbacks(svc.olaUnlockStrikeRunnable);
      svc.olaUnlockStrikeScheduled = false;
      if (notifText != null && !notifText.isEmpty()) {
        svc.rememberRideFare(parseRapidoPrice(notifText));
        float pk = parsePickupKm(notifText);
        if (pk > 0f) svc.lastRidePickupKm = pk;
      }
      // Arm → hunt → a11y tap FIRST. Rapido never waits on Shizuku.
      svc.arm(source + (acceptActionOk ? "/action" : ""));
      // Fast find only — getWindows/BFS on this looper delays the 1ms spray.
      boolean hunted = svc.huntAccept(packageName, tReceive, "NlsHunt+0", true);
      // If the active root is another app, scan Rapido overlay windows now in
      // this same front-of-queue task. Do not wait for the next poll/event.
      if (captain && !hunted && !svc.verifyingAccept && !svc.rapidoBurstLock) {
        hunted = svc.huntAccept(packageName, tReceive, "NlsOverlay+0", false);
      }
      if (!hunted && !svc.verifyingAccept) {
        svc.fireCachedAcceptStrike(packageName, tReceive, source);
      }
      if (acceptActionOk && !svc.verifyingAccept && !svc.rapidoBurstLock) {
        svc.beginVerify(packageName, "AcceptAction/" + source, tReceive, 0, 0, true);
      }
      svc.scheduleNlsFollowups(packageName, tReceive);
      if (!hunted && !svc.verifyingAccept && !svc.rapidoBurstLock) {
        svc.scheduleAcceptHuntPollImmediate();
      } else {
        svc.scheduleAcceptHuntPoll();
      }
      svc.scheduleContentIntentFallbackIfNeeded(
          packageName, notification, tReceive, acceptActionOk);
      svc.wakeScreenForRace();
      Log.i(TAG, "RIDE_SIGNAL " + source + " pkg=" + packageName
          + " action=" + acceptActionOk + " hunted=" + hunted
          + " shizuku=" + ShizukuInput.isReady());
    };
    if (Looper.myLooper() == Looper.getMainLooper()) {
      race.run();
    } else {
      svc.handler.postAtFrontOfQueue(race);
    }
  }

  public static void raceFromNotificationListener(String packageName, String notifText) {
    onRideSignal(packageName, null, notifText, SystemClock.uptimeMillis(), "NLS");
  }

  public static boolean tryFireAcceptPendingIntent(
      Notification n,
      String notifText,
      long tReceive,
      String source
  ) {
    if (n == null || (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled())) {
      return false;
    }
    // Static NLS path — same fare/pickup rules as UI taps (unknown → allow)
    if (!notifPassesMinPrice(notifText) || !notifPassesMaxPickup(notifText)) return false;
    Notification.Action action = findAcceptAction(n);
    if (action == null || action.actionIntent == null) return false;
    try {
      action.actionIntent.send();
      // Emit deferred to VERIFY — performAction/send success is not proof the ride was taken
      return true;
    } catch (Exception e) {
      Log.e(TAG, "ACCEPT_ACTION_FAIL", e);
      return false;
    }
  }

  /**
   * When minPrice &gt; 0: skip Accept PendingIntent only if fare is parsed and &lt; min.
   * Unknown / missing fare → still fire (competitors win; UI may lack ₹ in notif text).
   */
  private static boolean notifPassesMinPrice(String notifText) {
    int min = AutoClickerConfig.getMinPrice();
    if (min <= 0) return true;
    if (notifText == null || notifText.isEmpty()) return true;
    double price = parseRapidoPrice(notifText);
    if (price <= 0) return true;
    if (price < min) {
      logStaticMinSkip(price, min, "notif-below");
      return false;
    }
    return true;
  }

  /** When maxPickup &gt; 0: skip if first km is parsed and over the limit. Unknown → allow. */
  private static boolean notifPassesMaxPickup(String notifText) {
    float max = AutoClickerConfig.getMaxPickup();
    if (max <= 0f) return true;
    if (notifText == null || notifText.isEmpty()) return true;
    float pickup = parsePickupKm(notifText);
    return pickup <= 0f || pickup <= max;
  }

  private static void logStaticMinSkip(double price, int min, String why) {
  }

  private static Notification.Action findAcceptAction(Notification n) {
    if (n.actions != null) {
      for (Notification.Action a : n.actions) {
        if (a != null && isAcceptActionTitle(a.title)) return a;
      }
    }
    try {
      List<Notification.Action> wear = new Notification.WearableExtender(n).getActions();
      if (wear != null) {
        for (Notification.Action a : wear) {
          if (a != null && isAcceptActionTitle(a.title)) return a;
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  static boolean isAcceptActionTitle(CharSequence title) {
    if (title == null) return false;
    int len = title.length();
    if (len == 0 || len > 32) return false;
    return isRealAcceptLabelText(title);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    try {
      handleAccessibilityEvent(event);
    } catch (Throwable t) {
      Log.e(TAG, "A11Y_CRASH " + t.getMessage(), t);
    }
  }

  private void handleAccessibilityEvent(AccessibilityEvent event) {
    final long t0 = SystemClock.uptimeMillis();
    if (event == null) return;

    if (!engineIsOn()) {
      long now = SystemClock.uptimeMillis();
      if (now - lastSkipDisabledLogUptime >= 2000L) {
        lastSkipDisabledLogUptime = now;
        Log.w(TAG, "SKIP_DISABLED — Auto-accept OFF (turn ON in AG rider Home)");
      }
      return;
    }
    if (engineHaltedForMasterOff) {
      resumeEngineMasterOn("a11y-enabled");
    }

    CharSequence pkgCsEarly = event.getPackageName();
    String pkgEarly = pkgCsEarly != null ? pkgCsEarly.toString() : "";
    noteEventPackage(pkgEarly);

    // Ridio UI: never walk our tree. Never tap our screens.
    // Race may stay armed for overlay Accept in OTHER windows — but clear stale
    // mid-screen coords so we don't spray the center of Ridio.
    if (pkgEarly.equals(getPackageName())) {
      clearStaleAcceptTapPoints("self-ui");
      finishRapidoMicroBurst("self-ui");
      if (isRaceActive() || isStandbyHunting() || hasNonBubbleRapidoWindow()) {
        scheduleAcceptHuntPoll(); // hunt Rapido overlay windows only
      } else {
        stopAcceptHuntPoll("self-event");
      }
      return;
    }

    CharSequence pkgCs = event.getPackageName();
    String pkg = pkgCs != null ? pkgCs.toString() : "";
    int type = event.getEventType();

    if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
      if (!AutoClickerConfig.isPackageMonitored(pkg)) return;
      String text = notifText(event);
      boolean ola = AutoClickerConfig.isOlaPackage(pkg);
      boolean captain = AutoClickerConfig.isCaptainRapidoPackage(pkg);
      boolean driverPkg = ola || captain;
      Parcelable data = event.getParcelableData();
      Notification n = data instanceof Notification ? (Notification) data : null;
      boolean ongoing = n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
      if (!driverPkg && isSpamText(text)) return;
      if (driverPkg && isDriverStatusPing(pkg, text, ongoing)) return;
      // Rapido: real ride only. Ola: keep the working soft gate (driver pkg is enough).
      if (captain) {
        if (!isRideAlert(text, n) && !hasTripOfferCue(text)) return;
      } else if (!ola) {
        if (!isRideAlert(text, n) && !hasTripOfferCue(text)) return;
      }
      lastPkg = pkg;
      onRideSignal(pkg, n, text, t0, "A11yNotif");
      return;
    }

    if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      return;
    }

    boolean isRapidoHunt = isRapidoPackageName(pkg);
    // Null/empty packageName: allow when ride-app context already set (Android 15)
    boolean rapidoContext = isRapidoHunt
        || ((pkg == null || pkg.isEmpty()) && allowAsRapidoWindow(null, lastPkg));
    boolean windowsChanged = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

    // Accept race on Captain FG OR while NLS-armed
    if ((isRapidoHunt || rapidoContext)
        && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
      if (isRapidoHunt) lastPkg = pkg;
      syncRapidoForegroundFromActive("hot-path");
      if (shouldIdleForBubbleOnly()) {
        return;
      }
      if (!mayHuntAccept() || !canStartRapidoBurst()) {
        if (isRaceActive()) scheduleAcceptHuntPoll();
      } else {
      AccessibilityNodeInfo source = null;
      try {
        source = event.getSource();
        // Event source IS Accept — click before findByText / window walks
        if (source != null && canStartRapidoBurst()
            && (isRealAcceptLabel(nodeTextCs(source)) || viewIdLooksLikeAccept(source))) {
          smartClickAccept(
              AccessibilityNodeInfo.obtain(source),
              resolveRapidoPkg(source, pkg),
              "SrcInstant",
              t0);
          if (isRaceActive()) scheduleAcceptHuntPoll();
          return;
        }
        AccessibilityNodeInfo root = source != null ? source : safeRapidoRoot(pkg);
        if (root != null) {
          boolean ownRoot = source == null;
          try {
            // Skip bubble-sized event sources before any findByText on this root
            boolean bubbleRoot = isFloatingBubbleNode(root);
            if (!bubbleRoot) {
              try {
                AccessibilityWindowInfo rw = root.getWindow();
                if (rw != null) {
                  rw.getBoundsInScreen(scratchRect2);
                  if (isFloatingBubbleBounds(scratchRect2)) {
                    noteBubbleWindow(scratchRect2);
                    bubbleRoot = true;
                  }
                }
              } catch (Exception ignored) {
              }
            } else {
              try {
                root.getBoundsInScreen(scratchRect2);
                noteBubbleWindow(scratchRect2);
              } catch (Exception ignored) {
              }
            }
            // Compact OEM Accept sheets are often mis-tagged as bubble windows —
            // still search when Captain FG or race is armed.
            if (!bubbleRoot || isAcceptRaceHot() || rapidoForeground) {
              noteOnTripChrome(root);
              String rootPkg = resolveRapidoPkg(root, pkg);
              if (allowAsRapidoWindow(packageOf(root), pkg) || isAcceptRaceHot()) {
                AccessibilityNodeInfo accept = findAcceptFast(root);
                if (accept == null) {
                  accept = findAcceptByViewId(root);
                }
                if (accept == null) {
                  accept = findAcceptLabelInRoot(root);
                }
                if (accept != null) {
                  // Instant click — no pre-click logging (log is inside strike)
                  smartClickAccept(accept, rootPkg, "Rapido-HotPath", t0);
                  if (isRaceActive()) scheduleAcceptHuntPoll();
                  return;
                }
                // Do not geometry-tap unlabeled bars here — caused random FG taps.
              }
            }
          } finally {
            if (ownRoot) root.recycle();
          }
        }
      } finally {
        if (source != null) source.recycle();
      }
      // Captain FG (home OR nav/map): Accept often on sibling sheet — multi-window
      // Event-driven only; poll starts after arm/ui-sighted, not forever idle.
      if (rapidoForeground || isRaceActive() || hasLiveRapidoOverlayCard()
          || isStandbyHunting()) {
        if (huntAccept(lastPkg, t0, "FgMultiWin")) {
          if (isRaceActive() || isStandbyHunting()) scheduleAcceptHuntPoll();
          return;
        }
      }
      // Keep poll while racing, standby, or Captain FG
      if (isRaceActive() || isStandbyHunting() || rapidoForeground) {
        scheduleAcceptHuntPoll();
      }
      } // end mayHuntAccept
      // Fall through to routePackage below for standard dump path
    }

    // Strict package router: non-Rapido → return immediately
    // Exception: thin overlay Accept hunt when NLS-armed OR live overlay card
    if (!isRapidoHunt && !rapidoContext) {
      if (windowsChanged) {
        String active = resolveActivePackage();
        if (active != null && isTransientChromePackage(active)) {
          // Shade/heads-up — do not disarm FG hunt
          if (isRaceActive()) scheduleAcceptHuntPoll();
        } else if (active != null && !isRapidoPackageName(active)) {
          setRapidoForeground(false, "left/" + active);
        }
      }
      // Race, standby, or a live ride sheet over Maps/Home — never require NLS
      if ((isRaceActive() || isStandbyHunting() || hasNonBubbleRapidoWindow())
          && !shouldIdleForBubbleOnly()) {
        String huntPkg = lastPkg != null && isRapidoPackageName(lastPkg)
            ? lastPkg : AutoClickerConfig.PKG_OLA_DRIVER;
        huntAccept(huntPkg, t0, "ThinOverlay");
      } else if (!isRapidoInteractionBlocked()
          && !shouldIdleForBubbleOnly()) {
        sheetOverlayProbe(t0);
      }
      return;
    }

    if (isRapidoHunt) {
      lastPkg = pkg;
      syncRapidoForegroundFromActive("event-pkg");
    } else if (rapidoContext) {
      syncRapidoForegroundFromActive("event-null-pkg");
    }

    // Rapido fallback ladder
    AccessibilityNodeInfo source = null;
    try {
      source = event.getSource();
      AccessibilityNodeInfo root = source != null ? source : safeRapidoRoot(pkg);
      if (root == null) return;
      String rawRoot = packageOf(root);
      String rootPkg = rawRoot != null ? rawRoot : pkg;
      boolean rideApp = (rootPkg != null && isRapidoPackageName(rootPkg))
          || allowAsRapidoWindow(rawRoot, pkg);
      if (!rideApp) {
        if (source == null) root.recycle();
        return;
      }
      if (rawRoot == null && allowAsRapidoWindow(null, pkg)) {
        rootPkg = resolveRapidoPkg(root, pkg);
      }
      boolean ownRoot = source == null;
      try {
        routePackage(rootPkg, root, t0);
      } finally {
        if (ownRoot) root.recycle();
      }
    } finally {
      if (source != null) source.recycle();
    }

    if (isRaceActive()) scheduleAcceptHuntPoll();
  }

  /** Called from JS bridge when master/nuclear toggles change. */
  public static void onConfigChanged() {
    final AutoClickerService svc = sInstance;
    if (svc == null) return;
    svc.handler.post(() -> {
      if (!AutoClickerConfig.peekEnabled()) {
        svc.haltEngineMasterOff("master-off");
        return;
      }
      svc.resumeEngineMasterOn("config");
    });
  }

  /** Auto-accept is ON in this process (peek) or on disk (recovers missed broadcast). */
  private boolean engineIsOn() {
    return AutoClickerConfig.peekEnabled() || AutoClickerConfig.isEnabled();
  }

  private boolean isStandbyHunting() {
    return SystemClock.uptimeMillis() < standbyHuntUntilMs;
  }

  private void extendStandbyHunt(String reason) {
    standbyHuntUntilMs = SystemClock.uptimeMillis() + STANDBY_HUNT_MS;
    if (RACE_DEBUG_LOG) {
      Log.i(TAG, "STANDBY_HUNT " + reason + " ms=" + STANDBY_HUNT_MS);
    }
  }

  /** Keep the OEM-event watchdog alive for the life of the service. */
  private void ensureWatchdogRunning() {
    handler.removeCallbacks(raceWatchdogRunnable);
    handler.postDelayed(raceWatchdogRunnable, RACE_WATCHDOG_MS);
  }

  private void resumeEngineMasterOn(String reason) {
    engineHaltedForMasterOff = false;
    ensureWatchdogRunning();
    EngineKeepAlive.ensureStarted(this);
    RecentsGuard.ensureStarted(this);
    syncEngineForeground();
    if (shouldRunAcceptHuntPoll()) {
      scheduleAcceptHuntPoll();
    }
    Log.i(TAG, "ENGINE_RESUME " + reason);
  }

  /** Auto-accept OFF: cancel hunts, Ola unlock, bursts, and armed races. */
  private void haltEngineMasterOff(String reason) {
    handler.removeCallbacks(olaUnlockStrikeRunnable);
    olaUnlockStrikeScheduled = false;
    handler.removeCallbacks(nlsFollowRunnable);
    nlsFollowActive = false;
    handler.removeCallbacks(raceArmExpireRunnable);
    handler.removeCallbacks(pendingRideFlushRunnable);
    // Do NOT remove raceWatchdogRunnable — it must survive so ride 4+ can resume
    cancelContentIntentFallback(reason);
    pendingRideSignal = false;
    standbyHuntUntilMs = 0;
    if (engineHaltedForMasterOff) return;
    engineHaltedForMasterOff = true;
    cancelVerify(reason);
    finishRapidoMicroBurst(reason);
    stopAcceptHuntPoll(reason);
    raceArmedUntilMs = 0;
    raceArmedFromMs = 0;
    if (racePhase != RacePhase.IDLE) {
      setRacePhase(RacePhase.IDLE, reason);
    }
    TapHighlightOverlay.hideImmediate();
    syncEngineForeground();
    Log.i(TAG, "ENGINE_HALT " + reason + " auto-accept=OFF");
  }

  private void routePackage(String pkg, AccessibilityNodeInfo root, long t0) {
    if (isRapidoPackageName(pkg)) {
      handleRapido(root, t0, pkg);
    }
  }

  /** Find Accept by text (active root first, then all Rapido windows) → smartClick. */
  private boolean huntAccept(String hintPkg, long t0, String source) {
    return huntAccept(hintPkg, t0, source, false);
  }

  /**
   * @param fastOnly active-window findByText only. Skip getWindows / BFS so the
   *                 NLS 1ms spray is not blocked on ColorOS tree walks.
   */
  private boolean huntAccept(String hintPkg, long t0, String source, boolean fastOnly) {
    recoverIfRaceStuck("hunt/" + source);
    if (!mayHuntAccept()) return false;
    if (!engineIsOn()) return false;
    if (!canStartRapidoBurst()) {
      if (racePhase != RacePhase.VERIFYING && racePhase != RacePhase.STRIKING
          && racePhase != RacePhase.COOLDOWN) {
        logBlocked("hunt/" + source, "phase=" + racePhase);
      }
      return false;
    }
    if (shouldIdleForBubbleOnly()) {
      logBlocked("hunt/" + source, "bubble-only");
      return false;
    }
    long now = SystemClock.uptimeMillis();
    if (!isRaceActive() && lastEmptyHuntAtMs > 0
        && now - lastEmptyHuntAtMs < EMPTY_HUNT_COALESCE_MS) {
      return false;
    }

    AccessibilityNodeInfo hit = null;
    String hitPkg = hintPkg;
    boolean fromOverlay = false;
    boolean rapidoFg = rapidoForeground;
    if (!rapidoFg) {
      String activePkg = resolveActivePackage();
      rapidoFg = activePkg != null && AutoClickerConfig.isRapidoPackage(activePkg);
      if (rapidoFg) setRapidoForeground(true, "hunt-active");
    }

    // Multi-window when FG / armed / live overlay — never idle Captain chrome spray
    final boolean forceWindowHunt = isRaceActive() && !rapidoFg;
    final boolean huntActiveRoot = rapidoFg || isRaceArmed() || racePhase == RacePhase.ARMED
        || racePhase == RacePhase.VERIFYING;
    final boolean huntMulti = huntActiveRoot || hasLiveRapidoOverlayCard() || forceWindowHunt;

    // 1) ACTIVE ROOT FIRST — avoid getWindows before strike 0 when possible
    // Never hunt inside Ridio (com.ridio.app) — that caused center-screen spam.
    if (huntActiveRoot) {
      AccessibilityNodeInfo active = null;
      try {
        active = getRootInActiveWindow();
        if (active != null) {
          String rawActive = packageOf(active);
          if (rawActive != null && rawActive.equals(getPackageName())) {
            // Active window is our app — skip; multi-window hunt may still find overlay
            try { active.recycle(); } catch (Exception ignored) {}
            active = null;
          }
        }
        if (active != null) {
          // On-trip chrome must NOT abort hunt — Accept cards still show on nav/map
          if (allowAsRapidoWindow(packageOf(active), hintPkg)) {
            noteOnTripChrome(active);
          }
          String raw = packageOf(active);
          if (allowAsRapidoWindow(raw, hintPkg)) {
            boolean bubbleRoot = isFloatingBubbleNode(active);
            if (!bubbleRoot) {
              try {
                active.getBoundsInScreen(scratchRect2);
                bubbleRoot = isFloatingBubbleBounds(scratchRect2);
              } catch (Exception ignored) {
              }
            }
            if (!bubbleRoot || isAcceptRaceHot() || forceWindowHunt) {
              // REAL Accept text/label only — no bottom-CTA / random clickable heuristics
              hit = findAcceptFast(active);
              if (!fastOnly) {
                if (hit == null) hit = findAcceptByViewId(active);
                if (hit == null) hit = findAcceptLabelInRoot(active);
              }
              if (hit != null) hitPkg = resolveRapidoPkg(active, hintPkg);
            } else {
              try {
                active.getBoundsInScreen(scratchRect2);
                noteBubbleWindow(scratchRect2);
              } catch (Exception ignored) {
              }
            }
          }
        }
      } catch (Exception ignored) {
      } finally {
        if (active != null) active.recycle();
      }
    }

    // 2) Multi-window — Captain FG, armed, OR ride sheet over Home (all OEMs)
    if (hit == null && huntMulti && !fastOnly) {
      HuntHit multi = findAcceptAcrossRapidoWindows(hintPkg, rapidoFg || forceWindowHunt);
      if (multi != null) {
        hit = multi.node;
        hitPkg = multi.pkg;
        fromOverlay = multi.fromOverlay || forceWindowHunt;
      }
    }

    if (hit == null) {
      noteEmptyHunt(source);
      if ((rapidoFg || forceWindowHunt || isRaceArmed() || isStandbyHunting())
          && lastEmptyHuntAtMs - lastEmptyHuntLogMs >= 1500L) {
        lastEmptyHuntLogMs = lastEmptyHuntAtMs;
        Log.w(TAG, "HUNT_EMPTY src=" + source
            + " fg=" + rapidoFg
            + " armed=" + isRaceArmed()
            + " pkg=" + hintPkg
            + " lastPkg=" + lastPkg
            + " forceWin=" + forceWindowHunt
            + " phase=" + racePhase
            + " shizuku=" + ShizukuInput.isReady());
      }
      return false;
    }

    // Missed-order deferred to VERIFY (throttled) — don't delay FOUND→CLICK

    logFoundAccept(hit, source, hitPkg);
    return fireHuntHit(hit, hitPkg, fromOverlay, t0, source);
  }

  /** Result of a multi-window Accept hunt. Owns {@code node}. */
  private static final class HuntHit {
    final AccessibilityNodeInfo node;
    final String pkg;
    final boolean fromOverlay;

    HuntHit(AccessibilityNodeInfo node, String pkg, boolean fromOverlay) {
      this.node = node;
      this.pkg = pkg;
      this.fromOverlay = fromOverlay;
    }
  }

  /**
   * Scan every Rapido window for Accept text. Skips bubble-sized windows before findByText.
   * Prefers overlay / sheet windows when not FG; when FG accepts any non-bubble hit.
   * Wide sheets (≥50% / ≥280dp) kept only when they contain Accept.
   */
  private HuntHit findAcceptAcrossRapidoWindows(String hintPkg, boolean rapidoFg) {
    AccessibilityNodeInfo hit = null;
    String hitPkg = hintPkg;
    boolean fromOverlay = false;
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return null;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        AccessibilityNodeInfo root = null;
        try {
          try {
            w.getBoundsInScreen(scratchRect2);
            // Idle: skip float-icon windows. Armed race: still search — many OEMs
            // paint the ride Accept sheet at "bubble" size and we must find Accept.
            if (isFloatingBubbleBounds(scratchRect2)) {
              noteBubbleWindow(scratchRect2);
              if (!isAcceptRaceHot() && !rapidoFg) {
                continue;
              }
            }
          } catch (Exception ignored) {
          }
          root = w.getRoot();
          if (root == null) continue;
          String raw = packageOf(root);
          if (raw != null && raw.equals(getPackageName())) continue;
          if (!allowAsRapidoWindow(raw, hintPkg)) continue;
          String p = resolveRapidoPkg(root, hintPkg);
          boolean prefer = isLikelyOverlayWindow(w) || isRideCardSizedBounds(scratchRect2);

          // REAL Accept text/label only — heuristics caused random Captain taps
          AccessibilityNodeInfo candidate = findAcceptFast(root);
          if (candidate == null) {
            candidate = findAcceptByViewId(root);
          }
          if (candidate == null) {
            candidate = findAcceptLabelInRoot(root);
          }
          if (candidate == null) continue;

          // Drop tiny float-icon hits unless armed / FG
          try {
            AccessibilityWindowInfo nw = candidate.getWindow();
            if (nw != null) {
              nw.getBoundsInScreen(scratchRect);
              if (isFloatingBubbleBounds(scratchRect)
                  && !isAcceptRaceHot()
                  && !rapidoFg) {
                noteBubbleWindow(scratchRect);
                candidate.recycle();
                continue;
              }
            }
          } catch (Exception ignored) {
          }
          if (isFloatingBubbleNode(candidate)
              && !isAcceptRaceHot()
              && !rapidoFg) {
            candidate.recycle();
            continue;
          }

          if (hit != null) hit.recycle();
          hit = candidate;
          hitPkg = p;
          fromOverlay = prefer || !rapidoFg;
          // Prefer overlay/sheet; when FG take first Accept (sheet or main)
          if (prefer || rapidoFg) break;
        } finally {
          if (root != null) {
            try { root.recycle(); } catch (Exception ignored) {}
          }
        }
      }
    } catch (Exception ignored) {
    }
    if (hit == null) return null;
    return new HuntHit(hit, hitPkg, fromOverlay);
  }

  /** Shared Accept-hit → dual-strike → VERIFY. Owns {@code hit}. */
  private boolean fireHuntHit(
      AccessibilityNodeInfo hit,
      String hitPkg,
      boolean fromOverlay,
      long t0,
      String source
  ) {
    // HARD GATE: never arm/click without a real Accept label (blocks random CTAs).
    if (hit == null) return false;
    String pkg = hitPkg != null ? hitPkg : lastPkg;
    if (!isRealAcceptLabel(nodeTextCs(hit)) && !viewIdLooksLikeAccept(hit)) {
      try { hit.recycle(); } catch (Exception ignored) {}
      Log.w(TAG, "HUNT_REJECT not-accept-label src=" + source);
      return false;
    }
    noteHuntHit();
    lastHuntAtMs = SystemClock.uptimeMillis();
    lastPkg = pkg;
    // Do NOT refreshRaceArm before smartClick — that skipped live-offer checks
    // and let false hits go IDLE→STRIKING (random Captain taps).
    boolean started = smartClickAccept(hit, pkg,
        fromOverlay ? "OverlayHunt/" + source : "Hunt/" + source, t0);
    if (started || isRaceActive()) {
      refreshRaceArm("accept-sighted");
    }
    Log.w(TAG, "HUNT_HIT started=" + started
        + " src=" + source
        + " overlay=" + fromOverlay
        + " pkg=" + pkg
        + " phase=" + racePhase);
    if (acceptSuccessLatch) {
      return true;
    }
    if (!started && !rapidoBurstLock && !verifyingAccept) {
      lastEmptyHuntAtMs = SystemClock.uptimeMillis();
      return false;
    }
    int cx = rapidoBurstX > 0 ? rapidoBurstX : verifyCx;
    int cy = rapidoBurstY > 0 ? rapidoBurstY : verifyCy;
    if (fromOverlay) {
      if (hasOverlayCardBounds()) {
        scratchRect2.set(overlayCardBoundsRect);
      } else {
        scratchRect2.set(cx - 48, cy - 120, cx + 48, cy + 24);
      }
      if (cx > 0 && cy > 0 && !scratchRect2.contains(cx, cy)) {
        scratchRect2.union(cx - 24, cy - 24, cx + 24, cy + 24);
      }
      if (!isFloatingBubbleBounds(scratchRect2) && !pointInsideKnownBubble(cx, cy)) {
        markOverlayLive(scratchRect2, cx, cy);
        AutoClickerConfig.cacheOverlayTapPoint(pkg, overlayTapX, overlayTapY);
        setRideOverlayActive(true, "hunt-hit");
      }
    } else if (cx > 0 && cy > 0 && !pointInsideKnownBubble(cx, cy)) {
      cacheAcceptPoint(pkg, cx, cy);
    }
    return true;
  }

  private static String packageOf(AccessibilityNodeInfo node) {
    if (node == null) return null;
    CharSequence p = node.getPackageName();
    return p != null ? p.toString() : null;
  }

  /**
   * Fail-open for null packageName (Android 15): allow when hint/last/active is
   * a driver app or race is armed / overlay live.
   * Non-null packages that are not Ola/Rapido are refused.
   */
  private boolean allowAsRapidoWindow(String pkgFromNode, String hint) {
    // Never treat Ridio windows as Captain
    if (pkgFromNode != null && pkgFromNode.equals(getPackageName())) return false;
    if (pkgFromNode != null) return isRapidoPackageName(pkgFromNode);
    if (hint != null && isRapidoPackageName(hint)) return true;
    if (lastPkg != null && isRapidoPackageName(lastPkg)) return true;
    if (rapidoForeground || isRaceArmed() || hasLiveRapidoOverlayCard() || rideOverlayActive
        || isStandbyHunting()) {
      return true;
    }
    try {
      String active = resolveActivePackage();
      if (active != null && isRapidoPackageName(active)) return true;
    } catch (Exception ignored) {
    }
    return false;
  }

  /**
   * Identify Ola / Rapido from the node, hunt hint, last NLS pkg, then FG.
   * Never default to Rapido — that routed Ola into Accessibility clicks.
   */
  private AutoClickerConfig.TapTarget resolveTapTarget(AccessibilityNodeInfo node, String hint) {
    AutoClickerConfig.TapTarget t = AutoClickerConfig.tapTargetOf(packageOf(node));
    if (t != AutoClickerConfig.TapTarget.NONE) return t;
    t = AutoClickerConfig.tapTargetOf(hint);
    if (t != AutoClickerConfig.TapTarget.NONE) return t;
    t = AutoClickerConfig.tapTargetOf(lastPkg);
    if (t != AutoClickerConfig.TapTarget.NONE) return t;
    t = AutoClickerConfig.tapTargetOf(rapidoBurstPkg);
    if (t != AutoClickerConfig.TapTarget.NONE) return t;
    t = AutoClickerConfig.tapTargetOf(cachedAcceptPkg);
    if (t != AutoClickerConfig.TapTarget.NONE) return t;
    try {
      t = AutoClickerConfig.tapTargetOf(resolveActivePackage());
      if (t != AutoClickerConfig.TapTarget.NONE) return t;
    } catch (Exception ignored) {
    }
    return AutoClickerConfig.TapTarget.NONE;
  }

  /** Best ride-app package string for a node/window with possibly-null packageName. */
  private String resolveRapidoPkg(AccessibilityNodeInfo node, String hint) {
    AutoClickerConfig.TapTarget t = resolveTapTarget(node, hint);
    if (t != AutoClickerConfig.TapTarget.NONE) {
      String canon = AutoClickerConfig.canonicalPackage(t);
      String p = packageOf(node);
      if (p != null && AutoClickerConfig.tapTargetOf(p) == t) return p;
      if (hint != null && AutoClickerConfig.tapTargetOf(hint) == t) return hint;
      if (lastPkg != null && AutoClickerConfig.tapTargetOf(lastPkg) == t) return lastPkg;
      return canon;
    }
    String p = packageOf(node);
    if (p != null && isRapidoPackageName(p)) return p;
    if (hint != null && isRapidoPackageName(hint)) return hint;
    if (lastPkg != null && isRapidoPackageName(lastPkg)) return lastPkg;
    try {
      String active = resolveActivePackage();
      if (active != null && isRapidoPackageName(active)) return active;
      if (active != null && !active.equals("com.ridio.app")) return active;
    } catch (Exception ignored) {
    }
    if (p != null && !p.equals("com.ridio.app")) return p;
    if (hint != null && !hint.isEmpty()) return hint;
    if (lastPkg != null && !lastPkg.isEmpty()) return lastPkg;
    return null;
  }

  /**
   * Light Accept find — EN / HI / TE CTA stems via findByText.
   * Preferred on every Nuclear / low-end hot path. Never matches "Accepted" / अस्वीकार.
   */
  private AccessibilityNodeInfo findAcceptFast(AccessibilityNodeInfo root) {
    if (root == null) return null;
    AccessibilityNodeInfo found = firstAcceptByText(root, "Accept");
    if (found != null) return found;
    for (String search : ACCEPT_LABELS_FAST) {
      if ("Accept".equals(search)) continue;
      found = firstAcceptByText(root, search);
      if (found != null) return found;
    }
    return null;
  }

  /** View-id / resource-name Accept (image CTAs with empty text on some OEMs). */
  private AccessibilityNodeInfo findAcceptByViewId(AccessibilityNodeInfo root) {
    if (root == null) return null;
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < treeWalkCap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      String vid = null;
      try {
        vid = n.getViewIdResourceName();
      } catch (Exception ignored) {
      }
      if (vid != null) {
        String low = vid.toLowerCase(Locale.US);
        boolean idHit = low.contains("accept") && !low.contains("accepted")
            && !low.contains("reject");
        if (idHit) {
          n.getBoundsInScreen(scratchRect);
          if (!scratchRect.isEmpty()
              && scratchRect.width() >= 48
              && scratchRect.height() >= 32
              && !isExtremeTopChromeAccept(scratchRect)
              && !isOversizedAcceptBounds(scratchRect)) {
            while (!q.isEmpty()) q.removeFirst().recycle();
            return n;
          }
        }
      }
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return null;
  }

  /**
   * DISABLED — picked random bottom buttons on Captain home (NUCLEAR_CTA spam).
   * Keep stub so any stale call site compiles to a no-op.
   */
  private AccessibilityNodeInfo findSheetBottomCta(AccessibilityNodeInfo root) {
    return null;
  }

  /** Cheap short dump for ride-cue detection (no full tree string when possible). */
  private String collectRootDumpLite(AccessibilityNodeInfo root) {
    if (root == null) return null;
    scratchSb.setLength(0);
    ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>(32);
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < 48 && scratchSb.length() < 400) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      CharSequence t = nodeTextCs(n);
      if (t != null && t.length() > 0) {
        scratchSb.append(t).append(' ');
      }
      int kids = Math.min(n.getChildCount(), 8);
      for (int i = 0; i < kids; i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return scratchSb.toString();
  }

  /**
   * Reject full ride-card / sheet containers (center ≈ mid-screen spam).
   * Real Accept CTAs from Ola refs: wide bars, short height (~6–14% screen).
   * Full-width blue Accept at bottom of sheet is OK; tall white cards are not.
   */
  private boolean isOversizedAcceptBounds(Rect r) {
    if (r == null || r.isEmpty()) return true;
    ensureScreenMetrics();
    if (screenH <= 0 || screenW <= 0) return false;
    long area = (long) r.width() * (long) r.height();
    // Tall offer cards / half-screen sheets — not the Accept button
    if (r.height() >= screenH * 0.28f) return true;
    if (area > (long) (screenArea * 0.36f)) return true;
    // Full-bleed sheet body (wide + tall), not a flat Accept bar
    return r.width() >= screenW * 0.90f && r.height() >= screenH * 0.22f;
  }

  private boolean isExtremeTopChromeAccept(Rect r) {
    if (r == null || r.isEmpty()) return true;
    ensureScreenMetrics();
    if (screenH <= 0) return false;
    // Only extreme status strip — keep mid/upper overlay Accepts
    return r.centerY() < screenH * 0.06f;
  }

  private AccessibilityNodeInfo firstAcceptByText(AccessibilityNodeInfo root, String search) {
    List<AccessibilityNodeInfo> nodes;
    try {
      nodes = root.findAccessibilityNodeInfosByText(search);
    } catch (Exception e) {
      return null;
    }
    if (nodes == null) return null;
    AccessibilityNodeInfo found = null;
    for (AccessibilityNodeInfo node : nodes) {
      if (node == null) continue;
      if (found != null) {
        try { node.recycle(); } catch (Exception ignored) {}
        continue;
      }
      if (!isRealAcceptLabel(nodeTextCs(node))) {
        node.recycle();
        continue;
      }
      node.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty() || scratchRect.width() < 24 || scratchRect.height() < 16) {
        node.recycle();
        continue;
      }
      // Idle other apps: skip float-icon nodes. Captain FG / armed: keep Accept CTAs.
      if ((isBubbleLikeClickTarget(scratchRect) || isFloatingBubbleNode(node))
          && !isAcceptRaceHot() && !rapidoForeground) {
        node.recycle();
        continue;
      }
      // Extreme top chrome only — keep mid-card Accepts
      if (isExtremeTopChromeAccept(scratchRect)) {
        node.recycle();
        continue;
      }
      // Huge card/sheet nodes → center tap is mid-screen spam
      if (isOversizedAcceptBounds(scratchRect)) {
        node.recycle();
        continue;
      }
      found = node;
    }
    return found;
  }

  /** Find real Accept label anywhere in this tree (any screen position). */
  private AccessibilityNodeInfo findAcceptLabelInRoot(AccessibilityNodeInfo root) {
    if (root == null) return null;

    AccessibilityNodeInfo fast = findAcceptFast(root);
    if (fast != null) return fast;

    AccessibilityNodeInfo byId = findAcceptByViewId(root);
    if (byId != null) return byId;

    // Extra locale / wording variants (FAST already tried)
    for (String search : ACCEPT_LABELS_EXTRA) {
      AccessibilityNodeInfo found = firstAcceptByText(root, search);
      if (found != null) return found;
    }

    // Fallback BFS on contentDescription (some buttons have no getText)
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < treeWalkCap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      if (isRealAcceptLabel(nodeTextCs(n))) {
        n.getBoundsInScreen(scratchRect);
        if (!scratchRect.isEmpty() && scratchRect.width() >= 24
            && !isExtremeTopChromeAccept(scratchRect)
            && !isOversizedAcceptBounds(scratchRect)
            && (isAcceptRaceHot()
                || (!isBubbleLikeClickTarget(scratchRect) && !isFloatingBubbleNode(n)))) {
          while (!q.isEmpty()) q.removeFirst().recycle();
          return n;
        }
      }
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return null;
  }

  private boolean isRealAcceptLabel(CharSequence text) {
    return isRealAcceptLabelText(text);
  }

  /**
   * True for Accept CTAs in English / Hindi / Telugu (+ common Indian locales).
   * Rejects "Accepted", अस्वीकार (contains स्वीकार), Telugu past-tense forms, etc.
   */
  static boolean isRealAcceptLabelText(CharSequence text) {
    if (text == null) return false;
    int len = text.length();
    if (len == 0 || len > 48) return false;

    // English negatives
    if (containsIgnoreCase(text, "accepted") || containsIgnoreCase(text, "reject")
        || containsIgnoreCase(text, "decline") || containsIgnoreCase(text, "cancel")
        || containsIgnoreCase(text, "not accept") || containsIgnoreCase(text, "dismiss")) {
      return false;
    }

    // Hindi reject MUST run before स्वीकार — अस्वीकार contains स्वीकार as substring.
    if (indexOfSeq(text, "\u0905\u0938\u094d\u0935\u0940\u0915\u093e\u0930") >= 0 // अस्वीकार
        || indexOfSeq(text, "\u0905\u0938\u094d\u0935\u0940\u0915\u0943\u0924") >= 0 // अस्वीकृत
        || indexOfSeq(text, "\u092e\u0928\u093e \u0915\u0930") >= 0) { // मना कर
      return false;
    }
    // Telugu reject stems
    if (indexOfSeq(text, "\u0c24\u0c3f\u0c30\u0c38\u0c4d\u0c15\u0c30") >= 0 // తిరస్కర
        || indexOfSeq(text, "\u0c28\u0c3f\u0c30\u0c3e\u0c15\u0c30") >= 0) { // నిరాకర
      return false;
    }
    // Accepted / past-tense (exclude before accept stems)
    if (indexOfSeq(text, "\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a\u0c2c\u0c21") >= 0 // అంగీకరించబడ
        || indexOfSeq(text, "\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a\u0c2c\u0c21") >= 0 // స్వీకరించబడ
        || indexOfSeq(text, "\u0938\u094d\u0935\u0940\u0915\u093e\u0930 \u0915\u093f\u092f\u093e") >= 0 // स्वीकार किया
        || indexOfSeq(text, "\u0938\u094d\u0935\u0940\u0915\u0943\u0924") >= 0) { // स्वीकृत
      return false;
    }

    if (containsIgnoreCase(text, "accept")) {
      // Reject non-CTA phrases that contain "accept" (settings / legal / payments)
      if (containsIgnoreCase(text, "terms") || containsIgnoreCase(text, "policy")
          || containsIgnoreCase(text, "agreement") || containsIgnoreCase(text, "permission")
          || containsIgnoreCase(text, "location") || containsIgnoreCase(text, "payment")
          || containsIgnoreCase(text, "cookie") || containsIgnoreCase(text, "marketing")
          || containsIgnoreCase(text, "automatically") || containsIgnoreCase(text, "do not accept")
          || containsIgnoreCase(text, "won't accept") || containsIgnoreCase(text, "will not accept")) {
        return false;
      }
      // CTA-shaped only: short label or known Accept phrases
      if (len <= 28) return true;
      if (containsIgnoreCase(text, "accept in")
          || containsIgnoreCase(text, "accept trip")
          || containsIgnoreCase(text, "accept ride")
          || containsIgnoreCase(text, "accept now")
          || containsIgnoreCase(text, "accept booking")
          || containsIgnoreCase(text, "accept order")
          || containsIgnoreCase(text, "slide to accept")
          || containsIgnoreCase(text, "tap to accept")
          || containsIgnoreCase(text, "swipe to accept")) {
        return true;
      }
      return false;
    }
    if (equalsIgnoreCaseTrim(text, "take ride") || equalsIgnoreCaseTrim(text, "take order")) {
      return true;
    }

    // Hindi accept (+ transliteration)
    if (indexOfSeq(text, "\u0938\u094d\u0935\u0940\u0915\u093e\u0930") >= 0) return true; // स्वीकार
    if (indexOfSeq(text, "\u090f\u0915\u094d\u0938\u0947\u092a\u094d\u091f") >= 0) return true; // एक्सेप्ट

    // Telugu accept stems
    if (indexOfSeq(text, "\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a") >= 0) return true; // అంగీకరించ
    if (indexOfSeq(text, "\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a") >= 0) return true; // స్వీకరించ
    if (indexOfSeq(text, "\u0c06\u0c2e\u0c4b\u0c26\u0c3f\u0c02\u0c1a") >= 0) return true; // ఆమోదించ

    // Tamil / Kannada / Gujarati / Bengali
    if (indexOfSeq(text, "\u0b8f\u0bb1\u0bcd\u0bb1\u0bc1\u0b95\u0bcd\u0b95\u0bca\u0bb3") >= 0) return true; // ஏற்றுக்கொள்
    if (indexOfSeq(text, "\u0b8f\u0bb1\u0bcd\u0b95\u0bb5\u0bc1\u0bae") >= 0) return true; // ஏற்கவும
    if (indexOfSeq(text, "\u0cb8\u0ccd\u0cb5\u0cc0\u0c95\u0cb0\u0cbf\u0cb8") >= 0) return true; // ಸ್ವೀಕರಿಸ
    if (indexOfSeq(text, "\u0ab8\u0acd\u0ab5\u0ac0\u0a95\u0abe\u0ab0") >= 0) return true; // સ્વીકાર
    if (indexOfSeq(text, "\u0997\u09cd\u09b0\u09b9\u09a3") >= 0) return true; // গ্রহণ

    return false;
  }

  /** Prefer CharSequence over String alloc for Accept match. */
  private static CharSequence nodeTextCs(AccessibilityNodeInfo node) {
    if (node == null) return null;
    CharSequence t = node.getText();
    if (t != null && t.length() > 0) return t;
    CharSequence d = node.getContentDescription();
    if (d != null && d.length() > 0) return d;
    return null;
  }

  // ── CharSequence match helpers (no toLowerCase String alloc) ──────────────

  private static boolean containsIgnoreCase(CharSequence hay, String needle) {
    return indexOfIgnoreCase(hay, needle) >= 0;
  }

  private static int indexOfSeq(CharSequence hay, String needle) {
    if (hay == null || needle == null) return -1;
    int nLen = needle.length();
    int hLen = hay.length();
    if (nLen == 0 || nLen > hLen) return nLen == 0 ? 0 : -1;
    outer:
    for (int i = 0; i <= hLen - nLen; i++) {
      for (int j = 0; j < nLen; j++) {
        if (hay.charAt(i + j) != needle.charAt(j)) continue outer;
      }
      return i;
    }
    return -1;
  }

  private static int indexOfIgnoreCase(CharSequence hay, String needle) {
    if (hay == null || needle == null) return -1;
    int nLen = needle.length();
    int hLen = hay.length();
    if (nLen == 0) return 0;
    if (nLen > hLen) return -1;
    char n0 = Character.toLowerCase(needle.charAt(0));
    outer:
    for (int i = 0; i <= hLen - nLen; i++) {
      if (Character.toLowerCase(hay.charAt(i)) != n0) continue;
      for (int j = 1; j < nLen; j++) {
        if (Character.toLowerCase(hay.charAt(i + j))
            != Character.toLowerCase(needle.charAt(j))) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static boolean startsWithIgnoreCase(CharSequence hay, String prefix) {
    if (hay == null || prefix == null) return false;
    int nLen = prefix.length();
    if (hay.length() < nLen) return false;
    for (int i = 0; i < nLen; i++) {
      if (Character.toLowerCase(hay.charAt(i)) != Character.toLowerCase(prefix.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean equalsIgnoreCaseTrim(CharSequence hay, String expect) {
    if (hay == null || expect == null) return false;
    int start = 0;
    int end = hay.length();
    while (start < end && Character.isWhitespace(hay.charAt(start))) start++;
    while (end > start && Character.isWhitespace(hay.charAt(end - 1))) end--;
    int len = end - start;
    if (len != expect.length()) return false;
    for (int i = 0; i < len; i++) {
      if (Character.toLowerCase(hay.charAt(start + i))
          != Character.toLowerCase(expect.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  // ─── Rapido (micro-burst race) ────────────────────────────────────────────

  private void handleRapido(AccessibilityNodeInfo root, long t0, String pkg) {
    recoverIfRaceStuck("handle-rapido");
    // Captain FG (home) or NLS-armed — never on idle non-Captain screens
    if (!mayHuntAccept()) return;
    if (!canStartRapidoBurst()) return;
    if (shouldIdleForBubbleOnly()) return;
    if (root != null && isFloatingBubbleNode(root)) return;
    try {
      if (root != null) {
        AccessibilityWindowInfo w = root.getWindow();
        if (w != null) {
          w.getBoundsInScreen(scratchRect2);
          if (isFloatingBubbleBounds(scratchRect2)) {
            noteBubbleWindow(scratchRect2);
            return;
          }
        }
      }
    } catch (Exception ignored) {
    }
    // On-trip/nav chrome: note it, but KEEP hunting Accept (along-route offers)
    noteOnTripChrome(root);

    // Accept-first at 0ms for both Nuclear and Standard. Fare/pickup filters run
    // inside strikeRapidoInstant (skip dump-before-click — that added latency).
    AccessibilityNodeInfo accept = findAcceptFast(root);
    if (accept == null) {
      accept = findAcceptByViewId(root);
    }
    if (accept == null) {
      accept = findAcceptLabelInRoot(root);
    }
    if (accept == null) {
      accept = bfsFindAccept(root, true);
    }
    if (accept == null) {
      accept = bfsFindAccept(root, false);
    }
    if (accept != null) {
      if (driverOnTripChrome) {
        Log.i(TAG, "RAPIDO_ACCEPT while on-trip chrome");
      }
      String tag = AutoClickerConfig.isNuclearMode() ? "Rapido-Nuclear" : "Rapido";
      smartClickAccept(accept, pkg, tag, t0);
    }
  }

  private String dumpText(AccessibilityNodeInfo root) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < treeWalkCap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      CharSequence t = n.getText();
      if (t != null && t.length() > 0) sb.append(t).append(' ');
      CharSequence d = n.getContentDescription();
      if (d != null && d.length() > 0) sb.append(d).append(' ');
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return sb.toString();
  }

  private AccessibilityNodeInfo bfsFindAccept(AccessibilityNodeInfo root, boolean clickableOnly) {
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < treeWalkCap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      CharSequence text = nodeTextCs(n);
      boolean isAccept = isRealAcceptLabel(text);
      if (isAccept && (!clickableOnly || n.isClickable())) {
        n.getBoundsInScreen(scratchRect);
        if (isExtremeTopChromeAccept(scratchRect)) {
          n.recycle();
          continue;
        }
        if ((isBubbleLikeClickTarget(scratchRect) || isFloatingBubbleNode(n))
            && !isAcceptRaceHot() && !rapidoForeground) {
          n.recycle();
          continue;
        }
        if (isOversizedAcceptBounds(scratchRect)) {
          n.recycle();
          continue;
        }
        // Don't recycle n — caller owns it
        while (!q.isEmpty()) q.removeFirst().recycle();
        return n;
      }
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return null;
  }

  /**
   * Rapido Captain — 0ms Accessibility Accept click.
   * No Shizuku. Filters run on the ride card, then ACTION_CLICK immediately.
   * Owns {@code node}.
   */
  private boolean strikeRapidoInstant(
      AccessibilityNodeInfo node, String pkg, String tag, long findAt) {
    if (node == null) return false;
    AutoClickerConfig.TapTarget t = resolveTapTarget(node, pkg);
    if (t == AutoClickerConfig.TapTarget.OLA) {
      return strikeOla(node, AutoClickerConfig.PKG_OLA_DRIVER, "ola-from-rapido/" + tag, findAt);
    }
    if (t != AutoClickerConfig.TapTarget.RAPIDO) {
      try { node.recycle(); } catch (Exception ignored) {}
      Log.w(TAG, "RAPIDO_STRIKE_SKIP not-rapido target=" + t + " pkg=" + pkg);
      return false;
    }

    CharSequence acceptLabel = nodeTextCs(node);
    if (!isRealAcceptLabel(acceptLabel) && !viewIdLooksLikeAccept(node)) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    // Cheap geometry — reject only obvious junk before click
    try {
      node.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()
          || scratchRect.width() < 20
          || scratchRect.height() < 12
          || isExtremeTopChromeAccept(scratchRect)
          || isOversizedAcceptBounds(scratchRect)) {
        try { node.recycle(); } catch (Exception ignored) {}
        return false;
      }
    } catch (Exception e) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    final int cx = scratchRect.centerX();
    final int cy = scratchRect.centerY();

    if (!offerPassesFilters(node)) {
      cancelPredictivePulses();
      logMinSkip(lastRideFare, AutoClickerConfig.getMinPrice(), "rapido-instant");
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    // Keep NLS spray running — ACTION_CLICK is extra, not a replacement.
    // ── CLICK FIRST (0ms bookkeeping before this) ──────────────────────────
    AccessibilityNodeInfo clickTarget = resolveAcceptClickTarget(node);
    if (clickTarget == null) {
      clickTarget = AccessibilityNodeInfo.obtain(node);
    }
    boolean clicked = false;
    try {
      // Raw performAction — skip bubble/WhatsApp gates on the hot path
      if (clickTarget.isClickable()) {
        clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
      if (!clicked) {
        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
      if (!clicked && clickTarget.isClickable()) {
        clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
    } catch (Exception ignored) {
    }

    // 1ms press occupying the only Android gesture slot. 16ms dual-tap
    // was rejected/serialized and let other tappers land first.
    boolean gestOk = false;
    if (cx > 0 && cy > 0) {
      gestOk = dispatchGestureTapIndependent(cx, cy, TAP_MS_SHORT);
    }
    // OEM often drops the first ACTION_CLICK after a few rides — refresh + retry
    if (!clicked && !gestOk) {
      try { clickTarget.refresh(); } catch (Exception ignored) {}
      try {
        clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      } catch (Exception ignored) {
      }
      if (!clicked && cx > 0 && cy > 0) {
        gestOk = dispatchGestureTapIndependent(cx, cy, TAP_MS_SHORT);
      }
      if (!clicked && !gestOk) {
        noteGestureFail();
      }
    }

    // ── Bookkeeping AFTER click ────────────────────────────────────────────
    if (!isRaceActive()) {
      arm("ui-sighted/" + tag);
    }

    refreshRaceArm("rapido-instant");
    setRacePhase(RacePhase.STRIKING, tag);
    markAcceptFoundThisSignal();
    lastPkg = pkg != null ? pkg : lastPkg;
    lastRapidoAttemptMs = SystemClock.uptimeMillis();
    rapidoBurstLock = true;
    handler.removeCallbacks(rapidoMicroBurstRunnable);
    if (rapidoBurstNode != null && rapidoBurstNode != clickTarget) {
      try { rapidoBurstNode.recycle(); } catch (Exception ignored) {}
    }
    rapidoBurstNode = clickTarget;
    rapidoBurstX = cx;
    rapidoBurstY = cy;
    rapidoBurstPkg = pkg;
    rapidoBurstTag = tag;
    rapidoBurstT0 = findAt;
    rapidoBurstIndex = 0;
    verifyCx = cx;
    verifyCy = cy;
    if (cx > 0 && cy > 0) {
      cacheAcceptPoint(pkg, cx, cy);
      scratchRect2.set(scratchRect);
      markOverlayLive(scratchRect2, cx, cy);
      if (alertSprayActive) {
        alertSprayX = cx;
        alertSprayY = cy;
      }
    }
    node.recycle();

    final long findToClick = SystemClock.uptimeMillis() - findAt;
    Log.w(TAG, "STRIKE0 click=" + clicked + " gest=" + gestOk
        + " @" + cx + "," + cy
        + " ms=" + findToClick
        + " mode=rapido-instant"
        + " tag=" + tag);

    rapidoBurstFirstOk = clicked || gestOk;
    if (rapidoBurstFirstOk) {
      stashPendingAcceptHistory(pkg, (int) findToClick);
    }
    beginVerify(pkg, tag, findAt, cx, cy, false);

    long now = SystemClock.uptimeMillis();
    if (now >= rapidoRetryUntilMs) {
      rapidoRetryUntilMs = now + RAPIDO_RETRY_WINDOW_MS;
      handler.removeCallbacks(rapidoRetryWindowEndRunnable);
      handler.postDelayed(rapidoRetryWindowEndRunnable, RAPIDO_RETRY_WINDOW_MS);
    }
    // Instant follow-up clicks at same Accept (0 delay).
    // If NLS spray is already hammering this pixel, don't steal the main
    // looper with tree-walk micro-bursts.
    if (rapidoMicroExtraStrikes > 0 && !alertSprayActive) {
      handler.postAtFrontOfQueue(rapidoMicroBurstRunnable);
    } else if (rapidoMicroExtraStrikes <= 0) {
      finishRapidoMicroBurst("strike0-only");
    }
    return clicked || gestOk;
  }

  /**
   * Accept → fare/distance gate → dual-strike → VERIFY.
   * Rapido Captain uses a dedicated 0ms ACTION_CLICK path (see {@link #strikeRapidoInstant}).
   * Owns {@code node}.
   */
  private boolean smartClickAccept(AccessibilityNodeInfo node, String pkg, String tag, long t0) {
    // Clock starts the moment Accept is handed to us (found) — before fare/climb/click
    final long findAt = SystemClock.uptimeMillis();
    if (node == null) return false;
    if (!mayHuntAccept()) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    AutoClickerConfig.TapTarget preTarget = resolveTapTarget(node, pkg);
    if (preTarget == AutoClickerConfig.TapTarget.OLA) {
      if (rapidoBurstLock || racePhase == RacePhase.STRIKING
          || racePhase == RacePhase.VERIFYING) {
        finishRapidoMicroBurst("switch-to-" + preTarget);
      }
    } else if (isRapidoInteractionBlocked() || !canStartRapidoBurst()) {
      if (preTarget == AutoClickerConfig.TapTarget.RAPIDO) {
        breakRapidoCooldownForNewRide();
      } else {
        try { node.recycle(); } catch (Exception ignored) {}
        return false;
      }
    }
    if (!isRaceActive() && shouldIdleForBubbleOnly()) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    if (!nodeIsRapido(node) && preTarget == AutoClickerConfig.TapTarget.NONE) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    // Single router: Rapido a11y / Ola Shizuku-after-5s.
    // Never fall through to a shared click path — that sent Ola down Rapido a11y.
    AutoClickerConfig.TapTarget target = resolveTapTarget(node, pkg);
    String usePkg = resolveRapidoPkg(node, pkg);
    if (usePkg == null || usePkg.isEmpty()) {
      usePkg = AutoClickerConfig.canonicalPackage(target);
    }
    if (RACE_DEBUG_LOG) {
      Log.i(TAG, "TAP_ROUTE target=" + target
          + " pkg=" + usePkg
          + " hint=" + pkg
          + " last=" + lastPkg
          + " tag=" + tag
          + " shizuku=" + ShizukuInput.isReady());
    }

    if (target == AutoClickerConfig.TapTarget.RAPIDO) {
      return strikeRapidoInstant(node, usePkg, tag, findAt);
    }
    if (target == AutoClickerConfig.TapTarget.OLA) {
      return strikeOla(node, usePkg != null ? usePkg : AutoClickerConfig.PKG_OLA_DRIVER, tag, findAt);
    }
    try { node.recycle(); } catch (Exception ignored) {}
    Log.w(TAG, "TAP_ROUTE_SKIP unknown-target tag=" + tag);
    return false;
  }

  /**
   * Ola Driver — Shizuku only, after "ACCEPT IN N" (or 5s from first card sight).
   * Accessibility clicks are ignored by Ola. Owns {@code node}.
   */
  private boolean strikeOla(AccessibilityNodeInfo node, String pkg, String tag, long findAt) {
    if (node == null) return false;
    String usePkg = pkg != null && !pkg.isEmpty() ? pkg : AutoClickerConfig.PKG_OLA_DRIVER;
    lastPkg = usePkg;

    CharSequence label = nodeTextCs(node);
    if (!isRealAcceptLabel(label) && !viewIdLooksLikeAccept(node)) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    try {
      node.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()
          || scratchRect.width() < 20
          || scratchRect.height() < 12
          || isExtremeTopChromeAccept(scratchRect)
          || isOversizedAcceptBounds(scratchRect)) {
        try { node.recycle(); } catch (Exception ignored) {}
        return false;
      }
    } catch (Exception e) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    final int cx = scratchRect.centerX();
    final int cy = scratchRect.centerY();

    if (!isRaceActive()) {
      arm("ola-ui-sighted/" + tag);
    }

    // Countdown / 5s slider — warm coords, do not mark click fail
    if (!isAcceptTapReady(node, label, usePkg)) {
      noteOlaAcceptPending(node, label, usePkg, tag);
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    if (!offerPassesFilters(node)) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    if (!ensureShizukuReady()) {
      Log.w(TAG, "OLA_STRIKE_ABORT shizuku-not-ready @" + cx + "," + cy
          + " state=" + ShizukuInput.state(getApplicationContext()));
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    AccessibilityNodeInfo clickTarget = resolveAcceptClickTarget(node);
    if (clickTarget == null) {
      clickTarget = AccessibilityNodeInfo.obtain(node);
    }

    refreshRaceArm("ola-strike");
    setRacePhase(RacePhase.STRIKING, tag);
    markAcceptFoundThisSignal();
    verifyCx = cx;
    verifyCy = cy;
    if (cx > 0 && cy > 0) {
      cacheAcceptPoint(usePkg, cx, cy);
      scratchRect2.set(scratchRect);
      markOverlayLive(scratchRect2, cx, cy);
    }
    lastRapidoAttemptMs = SystemClock.uptimeMillis();
    rapidoBurstLock = true;
    rapidoBurstPkg = usePkg;
    rapidoBurstX = cx;
    rapidoBurstY = cy;

    boolean ok = injectOlaAcceptAt(cx, cy, clickTarget, true);

    node.recycle();
    handler.removeCallbacks(olaUnlockStrikeRunnable);
    olaUnlockStrikeScheduled = false;
    finishRapidoMicroBurst("ola-one-shot");

    Log.w(TAG, "OLA_STRIKE ok=" + ok
        + " @" + cx + "," + cy
        + " ms=" + (SystemClock.uptimeMillis() - findAt)
        + " label=" + label
        + " tag=" + tag
        + " shizuku=true");

    if (ok) {
      stashPendingAcceptHistory(usePkg, (int) (SystemClock.uptimeMillis() - findAt));
    }
    beginVerify(usePkg, tag, findAt, cx, cy, false);
    if (clickTarget != null) {
      try { clickTarget.recycle(); } catch (Exception ignored) {}
    }
    return ok;
  }

  /**
   * Sync ACTION_CLICK only. Skip refresh() on first attempt — it was a major latency source.
   * Refresh+retry only if the first click missed.
   */
  private boolean actionClickAccept(AccessibilityNodeInfo clickTarget) {
    if (clickTarget == null) return false;
    if (isRapidoInteractionBlocked()) return false;
    try {
      // Rapido FG / armed: raw click — skip heavy bubble gates
      String pkg = rapidoBurstPkg != null ? rapidoBurstPkg : lastPkg;
      if (AutoClickerConfig.isCaptainRapidoPackage(pkg)
          && (rapidoForeground || isAcceptRaceHot())) {
        if (clickTarget.isClickable()) {
          return clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
      if (!nodeIsRapido(clickTarget)) return false;
      if (isFloatingBubbleNode(clickTarget) && !isAcceptRaceHot() && !rapidoForeground) return false;
      try {
        clickTarget.getBoundsInScreen(scratchRect);
        if (isBubbleLikeClickTarget(scratchRect) && !isAcceptRaceHot() && !rapidoForeground) return false;
      } catch (Exception ignored) {
      }
      if (clickTarget.isClickable()) {
        boolean clicked = performRapidoClick(clickTarget);
        if (clicked) return true;
      }
      try { clickTarget.refresh(); } catch (Exception ignored) {}
      if (isFloatingBubbleNode(clickTarget) && !isAcceptRaceHot() && !rapidoForeground) return false;
      if (clickTarget.isClickable()) {
        return performRapidoClick(clickTarget);
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  /**
   * ACTION_CLICK first (Rapido node only), then short gesture (no callback wait).
   * Used by micro-burst strikes 1+ (strike 0 uses actionClickAccept + posted gesture).
   */
  private boolean dualStrikeAccept(
      AccessibilityNodeInfo clickTarget, int cx, int cy, boolean allowGesture) {
    boolean clicked = actionClickAccept(clickTarget);
    if (!allowGesture) {
      return clicked;
    }
    String pkg = rapidoBurstPkg != null ? rapidoBurstPkg : lastPkg;
    if (!AutoClickerConfig.needsShizukuInject(pkg)) {
      // Rapido micro: ACTION_CLICK only; gesture only on miss
      if (clicked && cx > 0 && cy > 0) {
        dispatchShortAndLongTap(cx, cy);
        return true;
      }
      return cx > 0 && cy > 0 && dispatchShortAndLongTap(cx, cy);
    }
    boolean gestOk = false;
    if (cx > 0 && cy > 0) {
      gestOk = strikeAcceptPoint(clickTarget, pkg, cx, cy, rapidoGestureMs);
    }
    return clicked || gestOk;
  }

  private void finishRapidoMicroBurst(String reason) {
    cancelPredictivePulses();
    handler.removeCallbacks(rapidoMicroBurstRunnable);
    long now = SystemClock.uptimeMillis();
    rapidoBurstLock = false;
    rapidoBurstX = 0;
    rapidoBurstY = 0;
    if (rapidoBurstNode != null) {
      try { rapidoBurstNode.recycle(); } catch (Exception ignored) {}
      rapidoBurstNode = null;
    }
    // Long CD only after verified Accept (accept-ok) or destroy — never on verify-miss
    if ("destroy".equals(reason) || "accept-ok".equals(reason)) {
      rapidoCooldownUntilMs = now + RAPIDO_POST_HIT_COOLDOWN_MS;
    }
    rapidoBurstPkg = null;
    rapidoBurstTag = null;
    rapidoBurstT0 = 0;
  }

  /**
   * Pick the node whose bounds center is the Accept tap point (ACTION_CLICK + gesture).
   * <ol>
   *   <li>Accept text itself if {@code isClickable()}</li>
   *   <li>Else nearest clickable ancestor that still contains Accept and is
   *       ≤ {@link #MAX_CLICK_AREA_RATIO}× Accept-text area (button-sized)</li>
   *   <li>Else {@code null} — caller taps Accept text center via gesture</li>
   * </ol>
   */
  private AccessibilityNodeInfo resolveAcceptClickTarget(AccessibilityNodeInfo accept) {
    if (accept == null) return null;
    accept.getBoundsInScreen(scratchRect);
    if (scratchRect.isEmpty()) return null;
    final int acceptArea = Math.max(1, scratchRect.width() * scratchRect.height());
    final int acceptCx = scratchRect.centerX();
    final int acceptCy = scratchRect.centerY();

    if (accept.isClickable()) {
      return AccessibilityNodeInfo.obtain(accept);
    }

    AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(accept);
    for (int d = 0; d < PARENT_CLIMB_CLICK && cur != null; d++) {
      AccessibilityNodeInfo parent = cur.getParent();
      cur.recycle();
      cur = parent;
      if (cur == null) break;
      if (!cur.isClickable()) continue;

      cur.getBoundsInScreen(scratchRect2);
      if (scratchRect2.isEmpty() || !scratchRect2.contains(acceptCx, acceptCy)) {
        continue;
      }
      long parentArea = (long) scratchRect2.width() * (long) scratchRect2.height();
      if (parentArea > (long) (MAX_CLICK_AREA_RATIO * acceptArea)) {
        // Full-card / sheet parent — center would miss Accept; use text + gesture
        cur.recycle();
        return null;
      }
      return cur;
    }
    if (cur != null) cur.recycle();
    return null;
  }

  // ─── Parsing ──────────────────────────────────────────────────────────────

  /**
   * Universal min fare + max pickup for Ola / Rapido UI taps.
   * Filter value 0 = off. Card text wins over stale NLS numbers.
   */
  private boolean offerPassesFilters(AccessibilityNodeInfo nearAccept) {
    int min = AutoClickerConfig.getMinPrice();
    float maxPickup = AutoClickerConfig.getMaxPickup();
    if (min <= 0 && maxPickup <= 0f) return true;

    String near = nearAccept != null ? collectPriceNearAccept(nearAccept) : "";
    double fare = parseRapidoPrice(near);
    float pickup = parsePickupKm(near);
    // Rapido overlay card is the whole window. Ola full-app dumps pick up
    // earnings chrome and false-skip (₹18 ride vs old ₹9/km / wallet text).
    String pkg = nearAccept != null ? packageOf(nearAccept) : lastPkg;
    if (((min > 0 && fare <= 0) || (maxPickup > 0f && pickup <= 0f))
        && AutoClickerConfig.isCaptainRapidoPackage(pkg)) {
      String win = dumpWindowText(nearAccept);
      if (win != null && !win.isEmpty()) {
        if (fare <= 0) fare = parseRapidoPrice(win);
        if (pickup <= 0f) pickup = parsePickupKm(win);
      }
    }
    if (fare <= 0) fare = lastRideFare;
    if (pickup <= 0f) pickup = lastRidePickupKm;
    return evaluateOfferFilters(fare, pickup);
  }

  /** Full dump / notification text — same rules as {@link #offerPassesFilters}. */
  private boolean offerPassesFiltersFromText(String text) {
    int min = AutoClickerConfig.getMinPrice();
    float maxPickup = AutoClickerConfig.getMaxPickup();
    if (min <= 0 && maxPickup <= 0f) return true;
    if (text == null || text.isEmpty()) return true;
    double fare = parseRapidoPrice(text);
    if (fare <= 0) fare = lastRideFare;
    float pickup = parsePickupKm(text);
    if (pickup <= 0f) pickup = lastRidePickupKm;
    return evaluateOfferFilters(fare, pickup);
  }

  private boolean evaluateOfferFilters(double fare, float pickup) {
    if (fare > 0) rememberRideFare(fare);
    if (pickup > 0f) lastRidePickupKm = pickup;
    int min = AutoClickerConfig.getMinPrice();
    if (min > 0 && fare > 0 && fare < min) {
      logMinSkip(fare, min, "filter");
      return false;
    }
    float maxPickup = AutoClickerConfig.getMaxPickup();
    if (maxPickup > 0f && pickup > 0f && pickup > maxPickup) {
      Log.i(TAG, "SKIP_PICKUP pickup=" + pickup + " max=" + maxPickup);
      return false;
    }
    return true;
  }

  /**
   * minPrice ≤ 0 → accept all.
   * Else: skip only when fare is parsed and &lt; min.
   * Unknown fare (₹ missing from tree — common on HyperOS) → allow with warning.
   * Near-Accept parse only — no full-tree dump before first click.
   */
  private boolean passesMinPrice(AccessibilityNodeInfo nearAccept) {
    return offerPassesFilters(nearAccept);
  }

  /**
   * Collect fare-ish text near Accept without a full dump (&lt;1ms path):
   * shallow parent climb + siblings (+ one grandchild level). Stops early on ₹.
   */
  private String collectPriceNearAccept(AccessibilityNodeInfo accept) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    if (accept == null) return "";
    AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(accept);
    try {
      for (int depth = 0; depth < 8 && cur != null && sb.length() < 480; depth++) {
        appendNodeChars(sb, cur);
        boolean hasFare = sb.indexOf("₹") >= 0 || containsIgnoreCase(sb, "rs");
        boolean hasKm = containsIgnoreCase(sb, "km") || containsIgnoreCase(sb, " m");
        if (hasFare && hasKm) break;
        AccessibilityNodeInfo parent = cur.getParent();
        if (parent == null) break;
        int n = parent.getChildCount();
        int max = Math.min(n, 12);
        for (int i = 0; i < max && sb.length() < 480; i++) {
          AccessibilityNodeInfo child = parent.getChild(i);
          if (child == null) continue;
          try {
            appendNodeChars(sb, child);
            int gcMax = Math.min(child.getChildCount(), 6);
            for (int j = 0; j < gcMax && sb.length() < 480; j++) {
              AccessibilityNodeInfo gc = child.getChild(j);
              if (gc == null) continue;
              try {
                appendNodeChars(sb, gc);
              } finally {
                gc.recycle();
              }
            }
          } finally {
            child.recycle();
          }
        }
        if ((sb.indexOf("₹") >= 0 || containsIgnoreCase(sb, "rs"))
            && (containsIgnoreCase(sb, "km") || containsIgnoreCase(sb, " m"))) {
          cur.recycle();
          cur = parent;
          break;
        }
        cur.recycle();
        cur = parent;
      }
    } finally {
      if (cur != null) {
        try { cur.recycle(); } catch (Exception ignored) {}
      }
    }
    return sb.toString();
  }

  private static void appendNodeChars(StringBuilder sb, AccessibilityNodeInfo n) {
    if (n == null) return;
    CharSequence t = n.getText();
    if (t != null && t.length() > 0) sb.append(t).append(' ');
    CharSequence d = n.getContentDescription();
    if (d != null && d.length() > 0) sb.append(d).append(' ');
  }

  private void logMinSkip(double price, int min, String why) {
    Log.i(TAG, "SKIP_FARE fare=" + price + " min=" + min + " why=" + why);
  }

  private static double parseRapidoPrice(String dump) {
    Matcher combo = PRICE_COMBO.matcher(dump);
    if (combo.find()) {
      return parseLooseDouble(combo.group(1)) + parseLooseDouble(combo.group(2));
    }
    Matcher single = PRICE_SINGLE.matcher(dump);
    if (single.find()) {
      return parseLooseDouble(single.group(1));
    }
    // Fallback: strip to digits after rupee-like
    Matcher any = PRICE_ANY.matcher(dump);
    if (any.find()) return parseLooseDouble(any.group(1));
    return 0;
  }

  /** Pickup km: labeled pickup first, else first on-screen distance (meters→km). */
  private static float parsePickupKm(String dump) {
    if (dump == null || dump.isEmpty()) return 0f;
    Matcher labeled = PICKUP_DIST.matcher(dump);
    if (labeled.find()) {
      return distToKm(labeled.group(1), labeled.group(2));
    }
    List<Float> kms = parseAllDistancesKm(dump);
    return kms.isEmpty() ? 0f : kms.get(0);
  }

  /** Distances in km in on-screen order. "800 m" → 0.8. First is pickup. */
  private static List<Float> parseAllDistancesKm(String dump) {
    List<Float> out = new ArrayList<>();
    if (dump == null || dump.isEmpty()) return out;
    Matcher m = DIST_ANY.matcher(dump);
    while (m.find()) {
      float km = distToKm(m.group(1), m.group(2));
      if (km > 0f) out.add(km);
    }
    return out;
  }

  private static float distToKm(String number, String unit) {
    double v = parseLooseDouble(number);
    if (v <= 0) return 0f;
    String u = unit != null ? unit.toLowerCase(Locale.US).replace(" ", "") : "km";
    if ("m".equals(u)) {
      if (v > 20000) return 0f;
      return (float) (v / 1000.0);
    }
    return (float) v;
  }

  /** Window dump around Accept — used when near-node walk missed fare/km. */
  private String dumpWindowText(AccessibilityNodeInfo node) {
    if (node == null) return "";
    AccessibilityNodeInfo root = null;
    try {
      AccessibilityWindowInfo w = node.getWindow();
      if (w != null) root = w.getRoot();
    } catch (Exception ignored) {
    }
    if (root == null) return collectPriceNearAccept(node);
    try {
      return dumpText(root);
    } finally {
      try { root.recycle(); } catch (Exception ignored) {}
    }
  }

  /** First km value in text (pickup). 0 if none. */
  private static float parseFirstKm(String dump) {
    return parsePickupKm(dump);
  }

  private static double parseLooseDouble(String text) {
    if (text == null) return 0;
    String cleaned = text.replaceAll("[^0-9.]", "");
    if (cleaned.isEmpty() || cleaned.equals(".")) return 0;
    try {
      return Double.parseDouble(cleaned);
    } catch (Exception e) {
      return 0;
    }
  }

  // ─── Gesture / helpers ────────────────────────────────────────────────────

  /**
   * Hard-gated absolute tap. Never taps the float-icon bubble.
   * Rapido FG → any non-bubble point OK.
   * Else only if (x,y) is inside a *live* Rapido overlay card.
   */
  private boolean gestureTap(int x, int y, long durationMs) {
    if (x <= 0 || y <= 0) return false;
    if (isRapidoInteractionBlocked()) return false;
    if (shouldIdleForBubbleOnly()) return false;
    // Absolute hard stop: never spray Ridio UI
    if (isSelfAppForeground() && !hasLiveRapidoOverlayCard()) return false;
    // canGestureAt enforces exact Accept center only (no random spray)
    if (!canGestureAt(x, y)) return false;
    if (AutoClickerConfig.needsShizukuInject(lastPkg)
        && ShizukuInput.tap(x, y, durationMs)) {
      return true;
    }
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;
    return dispatchGestureTap(x, y, durationMs);
  }

  /**
   * Strike-0 / VERIFY raw tap — caller already measured a live Accept LABEL center.
   * Skips canGestureAt (was blocking Vivo overlay taps when sticky mark failed).
   */
  private boolean gestureTapRaw(int x, int y, long durationMs) {
    if (x <= 0 || y <= 0) return false;
    if (isRapidoInteractionBlocked()) return false;
    String injectPkg = rapidoBurstPkg != null ? rapidoBurstPkg : lastPkg;
    if (!AutoClickerConfig.needsShizukuInject(injectPkg)) {
      if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;
      return dispatchGestureTap(x, y, durationMs);
    }
    if (ShizukuInput.tap(x, y, durationMs)) return true;
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;
    return dispatchGestureTap(x, y, durationMs);
  }

  /**
   * Inject Accept. Rapido → caller uses a11y. Ola → Shizuku swipe then tap.
   */
  private boolean strikeAcceptPoint(
      AccessibilityNodeInfo node, String pkg, int cx, int cy, long durationMs) {
    if (AutoClickerConfig.isCaptainRapidoPackage(pkg)) {
      return false; // Rapido uses ACTION_CLICK path only
    }
    if (needsSlideGesture(node)) {
      if (swipeAcceptBar(node, cx, cy, Math.max(180L, durationMs))) return true;
    }
    // Ola only: unlock bar / blue progress — hold + swipe + taps
    if (AutoClickerConfig.isOlaPackage(pkg)) {
      return injectOlaAcceptAt(cx, cy, node);
    }
    boolean shizukuReady = ShizukuInput.isReady();
    boolean tapped = gestureTapRaw(cx, cy, durationMs);
    if (tapped) {
      gestureTapRaw(cx, cy, Math.max(12L, durationMs));
    }
    if (!tapped) {
      Log.w(TAG, "STRIKE_TAP_FAIL xy=" + cx + "," + cy
          + " pkg=" + pkg
          + " shizukuReady=" + shizukuReady
          + " shizukuState=" + ShizukuInput.state(getApplicationContext()));
      tapped = dispatchGestureTap(cx, cy, durationMs);
    }
    return tapped;
  }

  /** Swipe left→right across Accept button bounds only — never synthesize a screen-wide swipe. */
  private boolean swipeAcceptBarSafe(AccessibilityNodeInfo node, int cx, int cy, long durationMs) {
    if (node == null || cx <= 0 || cy <= 0) return false;
    try {
      node.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()) return false;
      // Cap swipe to Accept label width (max ~70% screen) — never home-icon drag width
      ensureScreenMetrics();
      int maxW = Math.max(160, (int) (screenW * 0.70f));
      if (scratchRect.width() > maxW) {
        int mid = scratchRect.centerX();
        scratchRect.left = mid - maxW / 2;
        scratchRect.right = mid + maxW / 2;
      }
      if (scratchRect.width() < 48) {
        scratchRect.left = cx - 80;
        scratchRect.right = cx + 80;
      }
      int y = scratchRect.centerY();
      int pad = Math.max(16, scratchRect.width() / 10);
      int x1 = scratchRect.left + pad;
      int x2 = scratchRect.right - pad;
      if (x2 - x1 < 40) return false;
      long swipeMs = Math.max(140L, durationMs);
      if (ShizukuInput.swipe(x1, y, x2, y, swipeMs)) return true;
      return dispatchGestureSwipe(x1, y, x2, y, swipeMs);
    } catch (Exception e) {
      Log.w(TAG, "SWIPE_ACCEPT_SAFE " + e.getMessage());
      return false;
    }
  }

  /** Swipe left→right across Accept button bounds (Ola slider / progress Accept). */
  private boolean swipeAcceptBar(AccessibilityNodeInfo node, int cx, int cy, long durationMs) {
    // Prefer safe path — old synthetic screenW/3 swipe moved home icons
    if (node != null) {
      return swipeAcceptBarSafe(node, cx, cy, durationMs);
    }
    // No node: tiny local swipe only (never wide)
    try {
      int x1 = cx - 80;
      int x2 = cx + 80;
      int y = cy;
      long swipeMs = Math.max(140L, durationMs);
      if (ShizukuInput.swipe(x1, y, x2, y, swipeMs)) return true;
      return dispatchGestureSwipe(x1, y, x2, y, swipeMs);
    } catch (Exception e) {
      return false;
    }
  }

  private boolean needsSlideGesture(AccessibilityNodeInfo node) {
    if (node == null) return false;
    String h = "";
    try {
      CharSequence t = node.getText();
      CharSequence d = node.getContentDescription();
      h = ((t != null ? t : "") + " " + (d != null ? d : "")).toLowerCase(Locale.US);
    } catch (Exception ignored) {
    }
    return h.contains("slide to") || h.contains("swipe to accept") || h.contains("swipe to");
  }

  /**
   * Rapido: ready immediately.
   * Ola: ready only after {@link #OLA_UNLOCK_FROM_ALERT_MS} from race arm (initial alert).
   */
  private boolean isAcceptTapReady(AccessibilityNodeInfo node, CharSequence label, String pkg) {
    if (parseAcceptInCountdown(label) > 0) {
      olaSawCountdownThisRace = true;
    }
    // Rapido — instant, even if a11y reports disabled (countdown / Compose)
    if (AutoClickerConfig.isCaptainRapidoPackage(pkg)) {
      return true;
    }
    try {
      if (node != null && !node.isEnabled()) {
        // Still warm coords; Ola button may enable at unlock
        if (!AutoClickerConfig.isOlaPackage(pkg)) return false;
      }
    } catch (Exception ignored) {
    }
    if (!AutoClickerConfig.isOlaPackage(pkg)) {
      // Unknown target: treat as instant (do not apply Ola wait by mistake)
      return true;
    }
    return isOlaAcceptUnlocked(node, label);
  }

  /**
   * Ola unlock: "Accept in N" is locked. After that countdown disappears, tap now.
   * If we never saw numbers (progress bar only), wait 5s from first card sight.
   * Never use node.isEnabled() — Ola Compose often reports disabled while tappable.
   */
  private boolean isOlaAcceptUnlocked(AccessibilityNodeInfo node, CharSequence label) {
    int countdown = parseAcceptInCountdown(label);
    if (countdown > 0) {
      olaSawCountdownThisRace = true;
      return false;
    }
    if (olaSawCountdownThisRace) {
      return true;
    }
    long start = olaAcceptFirstSeenMs > 0 ? olaAcceptFirstSeenMs : raceArmedFromMs;
    if (start <= 0L) return false;
    return SystemClock.uptimeMillis() - start >= OLA_UNLOCK_FROM_ALERT_MS;
  }

  private static int parseAcceptInCountdown(CharSequence label) {
    if (label == null) return -1;
    Matcher m = ACCEPT_IN_COUNTDOWN.matcher(label);
    if (!m.find()) return -1;
    try {
      return Integer.parseInt(m.group(1));
    } catch (Exception e) {
      return 1;
    }
  }

  /** Warm Accept coords + keep arm alive while Ola waits alert+5s. Red box = target found. */
  private void noteOlaAcceptPending(
      AccessibilityNodeInfo node, CharSequence label, String pkg, String tag) {
    long now = SystemClock.uptimeMillis();
    boolean firstSight = olaAcceptFirstSeenMs <= 0L;
    if (firstSight) olaAcceptFirstSeenMs = now;
    int countdown = parseAcceptInCountdown(label);
    if (countdown > 0) olaSawCountdownThisRace = true;
    if (AutoClickerConfig.isOlaPackage(pkg)) {
      // Reschedule from card-sight / remaining countdown — not from the NLS ping
      if (firstSight) {
        olaUnlockStrikeScheduled = false;
        olaUnlockFireAtMs = 0L;
      }
      scheduleOlaUnlockStrikeIfNeeded(pkg, countdown);
    }
    try {
      node.getBoundsInScreen(scratchRect);
      if (!scratchRect.isEmpty() && !isOversizedAcceptBounds(scratchRect)) {
        int cx = scratchRect.centerX();
        int cy = scratchRect.centerY();
        if (cx > 0 && cy > 0) {
          cacheAcceptPoint(pkg, cx, cy);
          scratchRect2.set(scratchRect);
          markOverlayLive(scratchRect2, cx, cy);
          verifyCx = cx;
          verifyCy = cy;
        }
      }
    } catch (Exception ignored) {
    }
    refreshRaceArm("ola-wait/" + tag);
    scheduleAcceptHuntPoll();
    long start = olaAcceptFirstSeenMs > 0 ? olaAcceptFirstSeenMs : now;
    long left = OLA_UNLOCK_FROM_ALERT_MS - (now - start);
    if (now - lastOlaLockedLogMs > 700L) {
      lastOlaLockedLogMs = now;
      Log.i(TAG, "OLA_ACCEPT_WAIT label=" + (label != null ? label : "")
          + " countdown=" + countdown
          + " leftMs=" + Math.max(0, left)
          + " cardAgeMs=" + (now - start)
          + " shizuku=" + ShizukuInput.isReady()
          + " — wait for slider; one Shizuku tap when Accept unlocks");
    }
  }

  private boolean dispatchGestureSwipe(int x1, int y1, int x2, int y2, long durationMs) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;
    try {
      scratchPath.rewind();
      scratchPath.moveTo(x1, y1);
      scratchPath.lineTo(x2, y2);
      return dispatchGesture(
          new GestureDescription.Builder()
              .addStroke(new GestureDescription.StrokeDescription(
                  scratchPath, 0, Math.max(80L, durationMs)))
              .build(),
          null,
          null
      );
    } catch (Exception e) {
      Log.w(TAG, "SWIPE_FAIL " + e.getMessage());
      return false;
    }
  }

  /**
   * Short tap then longer press at the same Accept pixel.
   * Same sequence on every Android version / OEM — no model branch.
   */
  /**
   * One 1ms press. A back-to-back 16ms second stroke is rejected by Android
   * (one gesture at a time) and occupied the slot other tappers were using.
   */
  private boolean dispatchShortAndLongTap(int x, int y) {
    if (x <= 0 || y <= 0) return false;
    return dispatchGestureTapIndependent(x, y, TAP_MS_SHORT);
  }

  /**
   * Thread-safe gesture tap (own Path). Used from NLS binder + main.
   * {@link #scratchPath} is main-thread only.
   */
  private boolean dispatchGestureTapIndependent(int x, int y, long durationMs) {
    return dispatchGestureTapIndependent(x, y, durationMs, null);
  }

  private boolean dispatchGestureTapIndependent(
      int x, int y, long durationMs, GestureResultCallback cb) {
    if (x <= 0 || y <= 0) return false;
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;
    try {
      Path path = new Path();
      path.moveTo(x, y);
      return dispatchGesture(
          new GestureDescription.Builder()
              .addStroke(new GestureDescription.StrokeDescription(
                  path, 0, Math.max(1, durationMs)))
              .build(),
          cb,
          cb != null ? handler : null);
    } catch (Exception e) {
      return false;
    }
  }

  private boolean dispatchGestureTap(int x, int y, long durationMs) {
    try {
      scratchPath.rewind();
      scratchPath.moveTo(x, y);
      boolean ok = dispatchGesture(
          new GestureDescription.Builder()
              .addStroke(new GestureDescription.StrokeDescription(
                  scratchPath, 0, Math.max(1, durationMs)))
              .build(),
          null,
          null
      );
      if (!ok) {
        Log.w(TAG, "GESTURE_REJECT @" + x + "," + y + " d=" + durationMs);
        noteGestureFail();
      } else {
        consecutiveGestureFails = 0;
      }
      return ok;
    } catch (Exception e) {
      Log.w(TAG, "GESTURE_FAIL @" + x + "," + y + " " + e.getMessage());
      noteGestureFail();
      return false;
    }
  }

  private void noteGestureFail() {
    consecutiveGestureFails++;
    if (consecutiveGestureFails >= 4
        && SystemClock.uptimeMillis() - lastFrozenTreeRefreshMs >= FROZEN_TREE_REFRESH_COOLDOWN_MS) {
      refreshAccessibilityWindows("gesture-fail");
      consecutiveGestureFails = 0;
    }
  }

  /** Prefer a Rapido window root — never the Settings/Chrome active window. */
  private AccessibilityNodeInfo findRapidoRoot() {
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows != null) {
        AccessibilityNodeInfo bestOverlay = null;
        AccessibilityNodeInfo bestAny = null;
        for (AccessibilityWindowInfo w : windows) {
          if (w == null) continue;
          try {
            w.getBoundsInScreen(scratchRect2);
            if (isFloatingBubbleBounds(scratchRect2)) {
              noteBubbleWindow(scratchRect2);
              continue;
            }
          } catch (Exception ignored) {
          }
          AccessibilityNodeInfo r = w.getRoot();
          if (r == null) continue;
          String p = packageOf(r);
          if (!allowAsRapidoWindow(p, lastPkg)) {
            r.recycle();
            continue;
          }
          if (isLikelyOverlayWindow(w)) {
            if (bestOverlay != null) bestOverlay.recycle();
            bestOverlay = r;
            // Prefer Accept-bearing overlay
            AccessibilityNodeInfo accept = findAcceptLabelInRoot(r);
            if (accept != null) {
              accept.recycle();
              if (bestAny != null) bestAny.recycle();
              return bestOverlay;
            }
          } else if (bestAny == null) {
            bestAny = r;
          } else {
            r.recycle();
          }
        }
        if (bestOverlay != null) {
          if (bestAny != null) bestAny.recycle();
          return bestOverlay;
        }
        return bestAny;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private AccessibilityNodeInfo safeRapidoRoot(String hintPkg) {
    if (hintPkg != null && isRapidoPackageName(hintPkg)) {
      AccessibilityNodeInfo r = findRapidoRoot();
      if (r != null) return r;
    }
    // Fall back to active window when multi-window scan missed
    if (hintPkg != null && isRapidoPackageName(hintPkg)) {
      try {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
          String p = packageOf(active);
          if (p != null && isRapidoPackageName(p)) return active;
          active.recycle();
        }
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private AccessibilityNodeInfo safeRoot() {
    AccessibilityNodeInfo rapido = findRapidoRoot();
    if (rapido != null) return rapido;
    try {
      AccessibilityNodeInfo active = getRootInActiveWindow();
      if (active != null) {
        String p = packageOf(active);
        if (p != null && isRapidoPackageName(p)) {
          return active;
        }
        active.recycle();
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private String nodeText(AccessibilityNodeInfo node) {
    if (node == null) return null;
    CharSequence t = node.getText();
    if (t != null && t.length() > 0) return t.toString();
    CharSequence d = node.getContentDescription();
    if (d != null && d.length() > 0) return d.toString();
    return null;
  }

  private String notifText(AccessibilityEvent event) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    Parcelable data = event.getParcelableData();
    if (data instanceof Notification) {
      Notification n = (Notification) data;
      if (n.tickerText != null) sb.append(n.tickerText).append(' ');
      if (n.extras != null) {
        append(sb, n.extras.getCharSequence(Notification.EXTRA_TITLE));
        append(sb, n.extras.getCharSequence(Notification.EXTRA_TEXT));
        append(sb, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
      }
    }
    if (event.getText() != null) {
      for (CharSequence cs : event.getText()) append(sb, cs);
    }
    return sb.toString().toLowerCase(Locale.US);
  }

  private static void append(StringBuilder sb, CharSequence v) {
    if (v != null) sb.append(v).append(' ');
  }

  /** True if dump/notif text mentions an Accept CTA (EN / HI / TE / transliteration). */
  static boolean dumpHasAcceptCue(String original, String lowerOrSame) {
    String h = lowerOrSame != null ? lowerOrSame : original;
    if (h == null) return false;
    if (h.contains("accept") || h.contains("slide")) return true;
    String src = original != null ? original : h;
    return src.contains("\u0938\u094d\u0935\u0940\u0915\u093e\u0930") // स्वीकार
        || src.contains("\u090f\u0915\u094d\u0938\u0947\u092a\u094d\u091f") // एक्सेप्ट
        || src.contains("\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a") // అంగీకరించ
        || src.contains("\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a") // స్వీకరించ
        || src.contains("\u0c06\u0c2e\u0c4b\u0c26\u0c3f\u0c02\u0c1a") // ఆమోదించ
        || src.contains("\u0b8f\u0bb1\u0bcd\u0bb1\u0bc1\u0b95\u0bcd\u0b95\u0bca\u0bb3") // ஏற்றுக்கொள்
        || src.contains("\u0cb8\u0ccd\u0cb5\u0cc0\u0c95\u0cb0\u0cbf\u0cb8") // ಸ್ವೀಕರಿಸ
        || src.contains("\u0ab8\u0acd\u0ab5\u0ac0\u0a95\u0abe\u0ab0") // સ્વીકાર
        || src.contains("\u0997\u09cd\u09b0\u09b9\u09a3"); // গ্রহণ
  }

  static boolean isSpamText(String hay) {
    if (hay == null || hay.isEmpty()) return false;
    // Never spam-filter a trip offer.
    if (hasTripOfferCue(hay)) return false;
    return hay.contains("completed order") || hay.contains("total earning")
        || hay.contains("accepted orders") || hay.contains("login") || hay.contains("otp")
        || hay.contains("password") || hay.contains("wallet") || hay.contains("cashout")
        || hay.contains("payout") || hay.contains("rate your")
        || hay.contains("update available") || hay.contains("battery")
        || hay.contains("document") || hay.contains("training")
        || hay.contains("incentive") || hay.contains("bonus")
        || hay.contains("challenge") || hay.contains("go online") || hay.contains("you're online")
        || hay.contains("you are online") || hay.contains("offline") || hay.contains("duty")
        || hay.contains("kyc") || hay.contains("tip received") || hay.contains("payment received")
        || hay.contains("weekly") || hay.contains("leaderboard") || hay.contains("referral")
        || hay.contains("promotion") || hay.contains("offer ends") || hay.contains("recharge");
  }

  /**
   * Words that mean a live trip offer.
   */
  static boolean hasTripOfferCue(String text) {
    if (text == null || text.isEmpty()) return false;
    String h = text.toLowerCase(Locale.US);
    return h.contains("accept")
        || h.contains("\u0938\u094d\u0935\u0940\u0915\u093e\u0930") // स्वीकार
        || h.contains("\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a") // అంగీకరించ
        || h.contains("\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a") // స్వీకరించ
        || h.contains("\u090f\u0915\u094d\u0938\u0947\u092a\u094d\u091f") // एक्सेप्ट
        || h.contains("₹")
        || h.contains("new order") || h.contains("new ride") || h.contains("ride request")
        || h.contains("new trip") || h.contains("trip request") || h.contains("new booking")
        || h.contains("new request") || h.contains("incoming trip") || h.contains("incoming request")
        || h.contains("pickup") || h.contains("pick up")
        || h.contains("trip for you") || h.contains("opportunity")
        || h.contains("ride offer") || h.contains("order request")
        || ((h.contains("trip") || h.contains("request") || h.contains("ride"))
            && (h.contains("km") || h.contains("min") || h.contains("cash")
                || h.contains("bike") || h.contains("auto") || h.contains("fare")
                || h.contains("rs") || h.matches(".*\\d.*")))
        || ((h.contains("cash") || h.contains("bike") || h.contains("auto"))
            && (h.contains("km") || h.contains("min") || h.contains("₹") || h.contains("rs")));
  }

  /**
   * Online / KYC / earnings pings that are not a trip. Never used when
   * {@link #hasTripOfferCue} already matched.
   */
  static boolean isDriverStatusPing(String pkg, String text, boolean ongoing) {
    if (hasTripOfferCue(text)) return false;
    String h = text != null ? text.toLowerCase(Locale.US).trim() : "";
    if (h.contains("you're online") || h.contains("you are online")
        || h.contains("go online") || h.contains("offline")
        || h.contains("login") || h.contains("otp") || h.contains("kyc")
        || h.contains("document") || h.contains("training")
        || h.contains("update available") || h.contains("leaderboard")
        || h.contains("referral") || h.contains("wallet")
        || h.contains("total earning") || h.contains("weekly")
        || h.contains("duty")) {
      return true;
    }
    return false;
  }

  /**
   * True for ride-offer notifications — not earnings/status pings.
   * Nuclear: empty/unknown text still arms (overlay often paints before notif text).
   */
  static boolean isRideAlert(String notifText, Notification notification) {
    if (notification != null && findAcceptAction(notification) != null) {
      return true;
    }
    return looksLikeRideOffer(notifText);
  }

  static boolean looksLikeRideOffer(String text) {
    if (text == null || text.trim().isEmpty()) {
      return false;
    }
    if (hasTripOfferCue(text)) return true;
    String h = text.toLowerCase(Locale.US).trim();
    if (isSpamText(h)) return false;
    if (dumpHasAcceptCue(text, h)) return true;
    if (h.contains("new order") || h.contains("new ride") || h.contains("ride request")
        || h.contains("new trip") || h.contains("trip request") || h.contains("incoming trip")
        || h.contains("new booking") || h.contains("booking request")
        || h.contains("new request") || h.contains("trip alert")
        || h.contains("forwarded") || h.contains("opportunity")
        || h.contains("accept in") || h.contains("1 ride")) {
      return true;
    }
    if (h.contains("incoming") || h.contains("order request") || h.contains("ride offer")) {
      return true;
    }
    if (h.contains("pickup") || h.contains("pick up") || h.contains("drop")) return true;
    if (h.contains("booking") && (h.contains("₹") || h.contains("km") || h.contains("rs"))) {
      return true;
    }
    // Hindi / Telugu Captain notification cues (common on regional phones)
    if (text.contains("\u0928\u092f\u093e \u0911\u0930\u094d\u0921\u0930") // नया ऑर्डर
        || text.contains("\u0928\u092f\u0940 \u0930\u093e\u0907\u0921") // नई राइड
        || text.contains("\u092a\u093f\u0915\u0905\u092a") // पिकअप
        || text.contains("\u0930\u093e\u0907\u0921 \u0930\u093f\u0915\u094d\u0935\u0947\u0938\u094d\u091f") // राइड रिक्वेस्ट
        || text.contains("\u0c15\u0c4a\u0c24\u0c4d\u0c24 \u0c06\u0c30\u0c4d\u0c21\u0c30\u0c4d") // కొత్త ఆర్డర్
        || text.contains("\u0c15\u0c4a\u0c24\u0c4d\u0c24 \u0c30\u0c48\u0c21\u0c4d") // కొత్త రైడ్
        || text.contains("\u0c2a\u0c3f\u0c15\u0c2a\u0c4d")) { // పికప్
      return true;
    }
    // Fare or distance alone is a typical Captain ride heads-up
    if ((h.contains("₹") || h.contains("rs.") || h.contains("rs ") || h.contains("inr"))
        && h.matches(".*\\d.*")) {
      return true;
    }
    if (h.contains("km") && h.matches(".*\\d.*")) return true;
    return false;
  }


  /**
   * Live ride card near Accept. Refs: "Accept in 5", blue "Accept", ₹ / km / Bike • Cash.
   * Ola real Accept labels are enough — fare text may sit outside the near walk.
   */
  private boolean acceptLooksLikeLiveOffer(AccessibilityNodeInfo accept) {
    if (accept == null) return false;
    try {
      accept.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty() || isExtremeTopChromeAccept(scratchRect)) return false;
      if (isBubbleLikeClickTarget(scratchRect)) return false;
      if (isOversizedAcceptBounds(scratchRect)) return false;
    } catch (Exception e) {
      return false;
    }
    CharSequence label = nodeTextCs(accept);
    boolean real = isRealAcceptLabel(label) || viewIdLooksLikeAccept(accept);
    if (!real) return false;

    String pkg = packageOf(accept);
    if (pkg == null) pkg = lastPkg;
    // Reference UIs: Accept / Accept in N on Ola and Rapido = live offer
    if (AutoClickerConfig.isOlaPackage(pkg)
        || AutoClickerConfig.isCaptainRapidoPackage(pkg)) {
      return true;
    }
    if (label != null) {
      String lh = label.toString().toLowerCase(Locale.US);
      // Countdown Accept always means a live timed offer
      if (lh.contains("accept in") || lh.matches(".*accept\\s*\\d+.*")) return true;
    }
    String near = collectPriceNearAccept(accept);
    if (near == null) near = "";
    String h = near.toLowerCase(Locale.US);
    if (h.contains("₹") || h.contains("rs") || h.contains("inr") || h.contains("$")) return true;
    if (h.contains("km") || h.contains("pickup") || h.contains("drop") || h.contains("fare")) {
      return true;
    }
    if (h.contains("cash") || h.contains("bike") || h.contains("auto") || h.contains("cab")) {
      return true;
    }
    if (h.contains("min") && h.matches(".*\\d.*")) return true;
    if (h.contains("trip") || h.contains("request")) return true;
    if (parseRapidoPrice(near) > 0) return true;
    return false;
  }

  private static boolean viewIdLooksLikeAccept(AccessibilityNodeInfo node) {
    if (node == null) return false;
    try {
      String vid = node.getViewIdResourceName();
      if (vid == null) return false;
      String low = vid.toLowerCase(Locale.US);
      return low.contains("accept") && !low.contains("accepted") && !low.contains("reject");
    } catch (Exception e) {
      return false;
    }
  }

  /** Store positive fare for the current race (notification / UI parse). */
  private void rememberRideFare(double price) {
    if (price <= 0) return;
    int rupees = (int) Math.round(price);
    if (rupees <= 0) return;
    if (rupees > lastRideFare) {
      lastRideFare = rupees;
    }
  }

  private void clearPendingAcceptHistory() {
    pendingHistoryValid = false;
    pendingHistoryPkg = null;
    pendingHistoryMs = 0;
    pendingHistoryFare = 0;
    acceptSeenDuringVerify = false;
    historyConfirmUntilMs = 0;
    handler.removeCallbacks(historyConfirmRunnable);
  }

  /**
   * Remember strike metrics for later history. Does NOT emit / touch JS.
   * Safe on the hot path (no I/O, no bridge).
   */
  private void stashPendingAcceptHistory(String packageName, int findToClickMs) {
    if (raceEmitted) return;
    pendingHistoryValid = true;
    pendingHistoryPkg = packageName != null ? packageName : lastPkg;
    pendingHistoryMs = Math.max(0, findToClickMs);
    pendingHistoryFare = lastRideFare;
  }

  /**
   * Thin session emit after VERIFY confirmed Accept (History UI removed — no fare enrich).
   * Always async — never call synchronously from strike / hunt.
   */
  private void flushConfirmedAcceptHistory(String reason) {
    // Hard gate: must have stashed a real UI Accept strike
    if (raceEmitted || !pendingHistoryValid) {
      clearPendingAcceptHistory();
      return;
    }
    long now = SystemClock.uptimeMillis();
    if (now - lastEmitAtMs < 350) {
      clearPendingAcceptHistory();
      return;
    }
    raceEmitted = true;
    lastEmitAtMs = now;
    final String pkg = pendingHistoryPkg != null ? pendingHistoryPkg : lastPkg;
    final int ms = pendingHistoryMs;
    // Fare omitted (min-fare + History removed) — avoid extra tree/parse work
    final int fare = 0;
    final String mode = AutoClickerConfig.isNuclearMode() ? "Nuclear" : "Standard";
    clearPendingAcceptHistory();
    try {
      AutoClickerModule.emitRideAccepted(pkg, fare, "AcceptConfirmed", ms, mode);
      Log.i(TAG, "ACCEPT_EMIT reason=" + reason + " pkg=" + pkg + " ms=" + ms);
    } catch (Exception ignored) {
    }
  }

  private void logBlocked(String where, String why) {
    if (!RACE_DEBUG_LOG) return;
    Log.i(TAG, "BLOCKED " + where + " reason=" + why
        + " phase=" + racePhase
        + " fg=" + rapidoForeground
        + " armed=" + isRaceArmed());
  }

  private void logFoundAccept(AccessibilityNodeInfo node, String where, String pkg) {
    if (!RACE_DEBUG_LOG || node == null) return;
    try {
      node.getBoundsInScreen(scratchRect);
      Log.i(TAG, "FOUND_ACCEPT " + where
          + " @" + scratchRect.centerX() + "," + scratchRect.centerY()
          + " pkg=" + pkg);
    } catch (Exception ignored) {
    }
  }

  @Override
  public void onInterrupt() {
    Log.w(TAG, "A11Y_INTERRUPT");
  }
}
