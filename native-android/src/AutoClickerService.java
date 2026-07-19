package com.playnix.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
 * Race engine (Accessibility) — micro-burst Rapido path + Playnix NLS edge:
 *
 * 1) Rapido: Accept find → immediate dual/triple strike + 4–8 micro duals @~5ms
 *    → soft retry ~250ms while Accept visible → post-hit cooldown (not 3s after miss)
 * 2) Ola: view-ID filters + timed burst (unchanged, ~910ms window)
 * 3) NLS: Accept PendingIntent first → contentIntent → thin Accept hunt (0/2/5/10/18/30ms)
 * 4) Thin overlay: Accept-node click on Rapido windows when NLS-armed / WINDOWS_CHANGED
 * 5) Accept-hunt poll (~9ms) while Rapido FG only — NO coord spray (continuous OFF by default)
 *
 * HARD RULE: gestures only when Rapido FG or point inside a *live* Rapido overlay card.
 * ACTION_CLICK only on Rapido-package nodes. Never click floating bubble. No taps in
 * Settings/Chrome/etc. Playnix FG always pauses.
 */
public class AutoClickerService extends AccessibilityService {

  private static final String TAG = "AutoClickerService";
  /** Hot-path Log.i gated — flip true only when debugging latency/race. */
  private static final boolean DEBUG_LOG = false;

  private static final long COOLDOWN_MS = 3000;
  /**
   * Soft gap between micro-bursts while Accept still visible (retry window).
   * Must NOT be multi-second — a miss must re-strike within tens of ms.
   */
  private static final long RAPIDO_BURST_GAP_MS = 100;
  /** Keep retrying Accept while found after first hit (then post-hit cooldown). */
  private static final long RAPIDO_RETRY_WINDOW_MS = 250;
  /** After retry window / confirmed burst finishes — avoid double-accept spam. */
  private static final long RAPIDO_POST_HIT_COOLDOWN_MS = 1000;
  private static final long OLA_ACCEPT_WINDOW_MS = 910;
  private static final long OLA_GESTURE_MS = 8;
  private static final long OLA_BURST_INTERVAL_MS = 10;
  private static final int OLA_BURST_COUNT = 16;
  /** Short nuclear gesture (3–8ms) for race latency. */
  private static final long RAPIDO_GESTURE_MS = 5;
  /** Micro-burst dual-strike interval (4–6ms). */
  private static final long RAPIDO_MICRO_INTERVAL_MS = 5;
  /** Extra dual strikes after the immediate first (total hammer ~25–50ms). */
  private static final int RAPIDO_MICRO_EXTRA_STRIKES = 6;
  /** Legacy overlay CTA burst — unused on Accept-node path (kept for dead helpers). */
  private static final long RAPIDO_BURST_INTERVAL_MS = 5;
  private static final int RAPIDO_BURST_COUNT = 20;
  /** Legacy continuous spray interval (pref default OFF). */
  private static final long CONTINUOUS_INTERVAL_MS = 5;
  private static final long CONTINUOUS_GESTURE_MS = 3;
  /** Accept-text hunt poll while Rapido FG — NOT coord spray. */
  private static final long ACCEPT_HUNT_POLL_MS = 9;
  /** 0 for hot FG/overlay/event sources; tiny floor only for cold paths */
  private static final long HUNT_DEBOUNCE_MS = 0;
  private static final long HUNT_DEBOUNCE_COLD_MS = 4;
  /** Keep overlay bounds briefly after a transient miss (hunt only — NOT continuous) */
  private static final long OVERLAY_STICKY_MS = 1500;
  /** After NLS/ride ping, arm thin overlay Accept hunt (does not start continuous spray) */
  private static final long RACE_ARM_MS = 4500;
  private static final int TREE_WALK_CAP = 300;
  private static final int PARENT_CLIMB_CLICK = 20;
  private static final int PARENT_CLIMB_OLA = 4;
  private static final long HEARTBEAT_MS = 15_000;
  /** Continuous spray throttle only — unused when continuous pref is off */
  private static final long GESTURE_MIN_GAP_MS = 3;

  private static final String[] ACCEPT_LABELS = {
      "Accept", "ACCEPT", "accept",
      "Ride Accept", "Accept Ride", "Accept Now", "Accept Karo",
      "Accept Booking", "Accept Order", "Accept Trip", "Take Ride",
      "स्वीकार",
  };

  private static final String OLA_PRICE_ID = "com.olacabs.oladriver:id/tv_compact_price";
  private static final String OLA_PICKUP_ID = "com.olacabs.oladriver:id/tv_pickup_distance_time";
  private static final String OLA_DROP_ID = "com.olacabs.oladriver:id/tv_drop_distance_time";
  private static final String OLA_ACCEPT_ID = "com.olacabs.oladriver:id/btn_accept";

  private static final Pattern PRICE_COMBO =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)\\s*\\+\\s*₹\\s*(\\d+(?:\\.\\d+)?)");
  private static final Pattern PRICE_SINGLE =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)");
  private static final Pattern KM_PATTERN =
      Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*km", Pattern.CASE_INSENSITIVE);
  private static final Pattern FIRST_NUMBER =
      Pattern.compile("(\\d+(?:\\.\\d+)?)");
  private static final Pattern PRICE_ANY =
      Pattern.compile("(?:₹|rs\\.?|inr)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

  /** Overlay card CTA Y fractions — precomputed, no per-spray float[] alloc. */
  private static final float[] OVERLAY_CTA_FRACS = { 0.75f, 0.85f, 0.88f, 0.92f };

  /**
   * NLS follow-ups after immediate +0 hunt — Accept-hunt only (0, 2, 5, 10, 18, 30ms).
   * No overlay race / CTA spray / continuous.
   */
  private static final long[] NLS_FOLLOW_DELAYS_MS = { 2, 5, 10, 18, 30 };

  /** Reject chat-head / floating launcher bubbles (dp). */
  private static final int BUBBLE_MAX_DP = 140;
  private static final int BUBBLE_SQUARE_MAX_DP = 180;
  /** Minimum ride-card width/height (dp) when no Accept label is present. */
  private static final int RIDE_CARD_MIN_W_DP = 300;
  private static final int RIDE_CARD_MIN_H_DP = 120;
  /** Accept-containing overlay: still reject if smaller than this (dp). */
  private static final int ACCEPT_OVERLAY_MIN_W_DP = 200;
  private static final int ACCEPT_OVERLAY_MIN_H_DP = 80;

  private static volatile AutoClickerService sInstance;

  private final Handler handler = new Handler(Looper.getMainLooper());

  // ── Reused scratch objects (a11y/main thread only — never cross-thread) ──
  private final Rect scratchRect = new Rect();
  private final Rect scratchRect2 = new Rect();
  private final Path scratchPath = new Path();
  private final StringBuilder scratchSb = new StringBuilder(512);
  private final ArrayDeque<AccessibilityNodeInfo> scratchQueue = new ArrayDeque<>(64);
  /** Persistent overlay bounds — mutated via set(), never replaced on hot path. */
  private final Rect overlayCardBoundsRect = new Rect();

  private volatile long lastOlaAttemptMs = 0;
  /** Last micro-burst start — used with RAPIDO_BURST_GAP_MS inside retry window. */
  private volatile long lastRapidoAttemptMs = 0;
  /** Soft window: re-burst while Accept still visible (short gap only). */
  private volatile long rapidoRetryUntilMs = 0;
  /** Hard rest after retry window / burst success — not applied on a single miss. */
  private volatile long rapidoCooldownUntilMs = 0;
  private volatile long lastHuntAtMs = 0;
  private volatile long lastHeartbeatMs = 0;
  private volatile long lastEmitAtMs = 0;
  private volatile boolean olaBurstLock = false;
  private volatile boolean rapidoBurstLock = false;
  private volatile boolean acceptHuntPollScheduled = false;
  private volatile boolean rapidoForeground = false;
  /** Ride alert floating as SYSTEM_ALERT_WINDOW / overlay card (may show over Home). */
  private volatile boolean rideOverlayActive = false;
  private volatile long raceArmedUntilMs = 0;
  private volatile int overlayTapX = 0;
  private volatile int overlayTapY = 0;
  private volatile boolean overlayCardBoundsValid = false;
  private volatile boolean continuousScheduled = false;
  private volatile boolean overlayGestureBurstLock = false;
  private volatile String lastPkg = "com.rapido.rider";
  private volatile long continuousTapCount = 0;
  private volatile long lastGestureAtMs = 0;
  private volatile long overlayStickyUntilMs = 0;
  /** Live Rapido overlay confirmed by findBest — sticky alone must NOT unlock gestures. */
  private volatile long overlayLiveUntilMs = 0;

  // Screen metrics cache (refresh rarely)
  private int screenW;
  private int screenH;
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

  private final Runnable continuousTapRunnable = new Runnable() {
    @Override
    public void run() {
      continuousScheduled = false;
      // Strict: continuous 5ms spray only while Rapido Captain is foreground
      if (!shouldRunContinuousSpray()) {
        return;
      }

      // CRITICAL: never spray while user is inside Playnix — causes touch lag
      if (isPlaynixForeground()) {
        stopContinuous("playnix-fg");
        return;
      }

      continuousTapCount++;
      long t0 = SystemClock.uptimeMillis();

      // FIRST: Accept hunt every tick (0 debounce) — do not wait for heavy refresh
      huntAcceptAnywhere(lastPkg, t0, "FgHunt");

      // Heavy window scan every ~25ms (5 ticks @ 5ms) — not before first hunt
      if (continuousTapCount % 5 == 1) {
        refreshOverlayAndForeground("tick");
      }
      if (!shouldRunContinuousSpray() || isPlaynixForeground()) {
        // Left Rapido (or entered Playnix) — do not reschedule
        stopContinuous("tick-left-rapido");
        return;
      }

      if (!olaBurstLock && !rapidoBurstLock) sprayAcceptZonesInApp();

      // Heartbeat only — zero per-tick logging on continuous path
      if (t0 - lastHeartbeatMs >= HEARTBEAT_MS) {
        lastHeartbeatMs = t0;
        Log.i(TAG, "FG_CONTINUOUS_HB taps=" + continuousTapCount
            + " overlay=" + rideOverlayActive
            + " fg=" + rapidoForeground
            + " @" + overlayTapX + "," + overlayTapY);
      }

      scheduleContinuous();
    }
  };

  /**
   * Accept-hunt-only poll while Rapido FG — find Accept text + smartClick.
   * Never coord-sprays Settings/WhatsApp. Stops when not Rapido FG.
   */
  private final Runnable acceptHuntPollRunnable = new Runnable() {
    @Override
    public void run() {
      acceptHuntPollScheduled = false;
      if (!shouldRunAcceptHuntPoll()) {
        return;
      }
      long t0 = SystemClock.uptimeMillis();
      huntAcceptAnywhere(lastPkg, t0, "FgAcceptPoll");
      scheduleAcceptHuntPoll();
    }
  };

  /** Extra dual strikes after the immediate Accept hit (micro-burst hammer). */
  private final Runnable rapidoMicroBurstRunnable = new Runnable() {
    @Override
    public void run() {
      if (!AutoClickerConfig.isEnabled() || isPlaynixForeground()) {
        finishRapidoMicroBurst("abort");
        return;
      }
      if (rapidoBurstX > 0 && rapidoBurstY > 0 && !canGestureAt(rapidoBurstX, rapidoBurstY)
          && (rapidoBurstNode == null || !nodeIsRapido(rapidoBurstNode))) {
        finishRapidoMicroBurst("gate");
        return;
      }
      dualStrikeAccept(rapidoBurstNode, rapidoBurstX, rapidoBurstY);
      rapidoBurstIndex++;
      if (rapidoBurstIndex < RAPIDO_MICRO_EXTRA_STRIKES) {
        handler.postDelayed(this, RAPIDO_MICRO_INTERVAL_MS);
      } else {
        finishRapidoMicroBurst("done");
      }
    }
  };

  /** When soft retry window ends without a new burst, apply post-hit cooldown. */
  private final Runnable rapidoRetryWindowEndRunnable = new Runnable() {
    @Override
    public void run() {
      long now = SystemClock.uptimeMillis();
      if (rapidoBurstLock) {
        // Burst still running — re-check after it finishes
        handler.postDelayed(this, RAPIDO_MICRO_INTERVAL_MS * 2);
        return;
      }
      if (now >= rapidoRetryUntilMs && now >= rapidoCooldownUntilMs) {
        rapidoCooldownUntilMs = now + RAPIDO_POST_HIT_COOLDOWN_MS;
        dlog("RAPIDO_POST_HIT_CD after-retry-window cd=" + RAPIDO_POST_HIT_COOLDOWN_MS + "ms");
      }
    }
  };

  /** Coalesced NLS follow-up chain — single Runnable, no per-delay lambda alloc. */
  private final Runnable nlsFollowRunnable = new Runnable() {
    @Override
    public void run() {
      if (!AutoClickerConfig.isEnabled() || isPlaynixForeground()) {
        nlsFollowActive = false;
        return;
      }
      String pkg = nlsFollowPkg != null ? nlsFollowPkg : lastPkg;
      long t0 = nlsFollowT0;
      // Accept-hunt only — never overlay CTA spray / continuous
      huntAcceptAnywhere(pkg, t0, "NlsHunt");
      nlsFollowIndex++;
      if (nlsFollowIndex < NLS_FOLLOW_DELAYS_MS.length) {
        long delta = NLS_FOLLOW_DELAYS_MS[nlsFollowIndex]
            - NLS_FOLLOW_DELAYS_MS[nlsFollowIndex - 1];
        handler.postDelayed(this, Math.max(1, delta));
      } else {
        nlsFollowActive = false;
      }
    }
  };

  /** Start / restart coalesced NLS follow-ups (cancels prior chain). */
  private void scheduleNlsFollowups(String packageName, long tReceive) {
    handler.removeCallbacks(nlsFollowRunnable);
    nlsFollowPkg = packageName;
    nlsFollowT0 = tReceive;
    nlsFollowIndex = 0;
    nlsFollowActive = true;
    handler.postDelayed(nlsFollowRunnable, NLS_FOLLOW_DELAYS_MS[0]);
  }

  private static void dlog(String msg) {
    if (DEBUG_LOG) Log.i(TAG, msg);
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

  /**
   * Chat-head / floating Rapido launcher icon — never a ride Accept card.
   * Reject tiny, icon-sized, or near-square bubble windows.
   */
  private boolean isFloatingBubbleBounds(Rect r) {
    if (r == null || r.isEmpty()) return true;
    ensureScreenMetrics();
    int w = r.width();
    int h = r.height();
    int maxBubble = dp(BUBBLE_MAX_DP);
    if (w <= maxBubble && h <= maxBubble) return true;
    int squareMax = dp(BUBBLE_SQUARE_MAX_DP);
    if (w <= squareMax && h <= squareMax) {
      float ratio = w >= h ? (float) w / (float) h : (float) h / (float) w;
      if (ratio <= 1.35f) return true; // circle / chat-head
    }
    // Degenerate strip (icon chrome)
    if (w < dp(80) || h < dp(80)) return true;
    return false;
  }

  /** Substantial ride-offer card (width ~half screen or ≥300dp, reasonable height). */
  private boolean isRideCardSized(Rect r) {
    if (r == null || r.isEmpty() || isFloatingBubbleBounds(r)) return false;
    ensureScreenMetrics();
    int minW = Math.max(dp(RIDE_CARD_MIN_W_DP), (int) (screenW * 0.50f));
    int minH = dp(RIDE_CARD_MIN_H_DP);
    if (r.width() >= minW && r.height() >= minH) return true;
    // Wide bottom-sheet / dialog style
    if (r.width() >= (int) (screenW * 0.45f) && r.height() >= dp(160)) return true;
    return false;
  }

  /** Accept may live in a slightly smaller overlay, but never in a bubble. */
  private boolean isValidAcceptOverlayBounds(Rect r) {
    if (r == null || r.isEmpty() || isFloatingBubbleBounds(r)) return false;
    return r.width() >= dp(ACCEPT_OVERLAY_MIN_W_DP)
        && r.height() >= dp(ACCEPT_OVERLAY_MIN_H_DP);
  }

  /**
   * Shallow scan for ride-offer signals (₹ / km / fare) when Accept is absent.
   * Does not call findAcceptLabelInRoot (caller already checked).
   */
  private boolean rootLooksLikeRideCard(AccessibilityNodeInfo root) {
    if (root == null) return false;
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    final int cap = 80;
    while (!q.isEmpty() && walked < cap) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      CharSequence t = nodeTextCs(n);
      if (t != null && t.length() > 0) {
        if (indexOfSeq(t, "₹") >= 0
            || containsIgnoreCase(t, "km")
            || containsIgnoreCase(t, "fare")
            || containsIgnoreCase(t, "pickup")
            || containsIgnoreCase(t, "drop")
            || containsIgnoreCase(t, "rs")
            || containsIgnoreCase(t, "new ride")
            || containsIgnoreCase(t, "ride request")) {
          while (!q.isEmpty()) q.removeFirst().recycle();
          n.recycle();
          return true;
        }
      }
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo c = n.getChild(i);
        if (c != null) q.add(c);
      }
      n.recycle();
    }
    while (!q.isEmpty()) q.removeFirst().recycle();
    return false;
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    sInstance = this;
    AutoClickerConfig.init(this);

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
    Log.i(TAG, "ENGINE_STARTED micro-burst + NLS edge"
        + " burstGap=" + RAPIDO_BURST_GAP_MS + "ms"
        + " retryWin=" + RAPIDO_RETRY_WINDOW_MS + "ms"
        + " postHitCd=" + RAPIDO_POST_HIT_COOLDOWN_MS + "ms"
        + " gest=" + RAPIDO_GESTURE_MS + "ms"
        + " acceptPoll=" + ACCEPT_HUNT_POLL_MS + "ms"
        + " continuous=" + AutoClickerConfig.isContinuousForegroundTap()
        + " nuclear=" + AutoClickerConfig.isNuclearMode()
        + " minPrice=" + AutoClickerConfig.getMinPrice());
  }

  @Override
  public void onDestroy() {
    stopContinuous("destroy");
    stopAcceptHuntPoll("destroy");
    handler.removeCallbacks(rapidoMicroBurstRunnable);
    handler.removeCallbacks(rapidoRetryWindowEndRunnable);
    finishRapidoMicroBurst("destroy");
    if (sInstance == this) sInstance = null;
    super.onDestroy();
  }

  /**
   * Continuous spray — Rapido FG ONLY, and only if continuous_fg_tap pref is on.
   * Nuclear mode alone does NOT enable spray (Accept-hunt poll wins by default).
   */
  private boolean shouldRunContinuousSpray() {
    if (!AutoClickerConfig.isEnabled()) return false;
    if (!AutoClickerConfig.isContinuousForegroundTap()) return false;
    if (isPlaynixForeground()) return false;
    return rapidoForeground;
  }

  /**
   * Light Accept-text hunt while Rapido FG — no coord spray.
   * Skipped when legacy continuous spray already hunts every tick.
   */
  private boolean shouldRunAcceptHuntPoll() {
    if (!AutoClickerConfig.isEnabled()) return false;
    if (isPlaynixForeground()) return false;
    if (!rapidoForeground) return false;
    if (shouldRunContinuousSpray()) return false; // continuous path already hunts
    return true;
  }

  private void scheduleAcceptHuntPoll() {
    if (acceptHuntPollScheduled) return;
    if (!shouldRunAcceptHuntPoll()) return;
    acceptHuntPollScheduled = true;
    handler.postDelayed(acceptHuntPollRunnable, ACCEPT_HUNT_POLL_MS);
  }

  private void stopAcceptHuntPoll(String reason) {
    handler.removeCallbacks(acceptHuntPollRunnable);
    acceptHuntPollScheduled = false;
    dlog("ACCEPT_HUNT_POLL_STOP reason=" + reason);
  }

  /**
   * True when a new Rapido Accept micro-burst may start.
   * Mid-burst locked; post-hit hard CD; soft gap only inside retry window.
   */
  private boolean canStartRapidoBurst() {
    if (rapidoBurstLock) return false;
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
   * True when Rapido is FG or a *live* Rapido overlay/ride card is on screen.
   * Used for event-driven Accept hunt / overlay race — NOT for continuous spray.
   * Race-armed / sticky alone is insufficient for absolute taps.
   */
  private boolean canInteractNow() {
    return rapidoForeground || hasLiveRapidoOverlayCard();
  }

  /**
   * Hunt/scheduling surface: Rapido FG, live overlay, or sticky card (hunt only).
   * Absolute gestures still require canGestureAt / canInteractNow.
   */
  private boolean hasRapidoSurface() {
    return canInteractNow()
        || (rideOverlayActive && hasOverlayCardBounds());
  }

  private boolean nodeIsRapido(AccessibilityNodeInfo node) {
    if (node == null) return false;
    String p = packageOf(node);
    return p != null && isRapidoPackageName(p);
  }

  private boolean pointInsideOverlayCard(int x, int y) {
    return hasOverlayCardBounds() && overlayCardBoundsRect.contains(x, y);
  }

  /**
   * Absolute-coord tap gate: Rapido FG, or point inside a *live* Rapido overlay card.
   * Never allow screen-bottom / cached sprays over Settings/Chrome/etc.
   */
  private boolean canGestureAt(int x, int y) {
    if (isPlaynixForeground()) return false;
    if (rapidoForeground) return true;
    // Ola burst uses gestures while Ola is FG — separate from Rapido leak path
    try {
      String active = resolveActivePackage();
      if (active != null && AutoClickerConfig.isOlaPackage(active)) return true;
    } catch (Exception ignored) {
    }
    return hasLiveRapidoOverlayCard() && pointInsideOverlayCard(x, y);
  }

  private void markOverlayLive(Rect bounds, int tapX, int tapY) {
    if (bounds == null || bounds.isEmpty()) return;
    // Never authorize absolute gestures on the floating Rapido bubble
    if (isFloatingBubbleBounds(bounds)) {
      dlog("OVERLAY_REJECT_BUBBLE markLive "
          + bounds.width() + "x" + bounds.height());
      return;
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
    // Keep sticky hunt arm briefly, but revoke live-tap authorization
    overlayLiveUntilMs = 0;
    dlog("CLEAR_BLIND_SPRAY reason=" + reason);
  }

  /** ACTION_CLICK only on Rapido-package nodes (null/non-Rapido refused). */
  private boolean performRapidoClick(AccessibilityNodeInfo node) {
    if (node == null) {
      Log.w(TAG, "BLOCKED_TAP pkg=null reason=null-node");
      return false;
    }
    String p = packageOf(node);
    if (p == null || !isRapidoPackageName(p)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + p + " reason=non-rapido-node");
      return false;
    }
    try {
      return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isPlaynixForeground() {
    try {
      String p = resolveActivePackage();
      return p != null && p.equals(getPackageName());
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isRaceArmed() {
    return SystemClock.uptimeMillis() <= raceArmedUntilMs;
  }

  /** Throttled gesture — prevents input queue flood / Playnix UI lag. */
  private boolean throttledGesture(int x, int y) {
    long now = SystemClock.uptimeMillis();
    if (now - lastGestureAtMs < GESTURE_MIN_GAP_MS) return false;
    lastGestureAtMs = now;
    return gestureTap(x, y, CONTINUOUS_GESTURE_MS);
  }

  /**
   * In-app FG spray only. Hard-requires Rapido FG — never blind-tap over other apps.
   */
  private void sprayAcceptZonesInApp() {
    if (!rapidoForeground) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + resolveActivePackage()
          + " reason=spray-requires-rapido-fg");
      return;
    }
    int[] cached = AutoClickerConfig.getKnownTapPoint(lastPkg);
    if (cached != null) {
      throttledGesture(cached[0], cached[1]);
      gestureTap(cached[0], cached[1], CONTINUOUS_GESTURE_MS);
      return;
    }
    // No screen-bottom invent — only known Accept coords while FG
  }

  /**
   * Overlay card CTA band — card-relative; points must be inside live Rapido bounds.
   * Never spray the floating bubble / icon window.
   */
  private void sprayOverlayCard(Rect card, String reason) {
    if (card == null || card.isEmpty()) return;
    if (isFloatingBubbleBounds(card) || !isRideCardSized(card)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + resolveActivePackage()
          + " reason=overlay-spray-not-ride-card source=" + reason
          + " size=" + card.width() + "x" + card.height());
      return;
    }
    if (!rapidoForeground && !hasLiveRapidoOverlayCard()) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + resolveActivePackage()
          + " reason=overlay-spray-no-live-card source=" + reason);
      return;
    }
    int cx = card.centerX();
    int h = card.height();
    int top = card.top;
    for (int i = 0; i < OVERLAY_CTA_FRACS.length; i++) {
      int y = top + (int) (h * OVERLAY_CTA_FRACS[i]);
      gestureTap(cx, y, CONTINUOUS_GESTURE_MS);
      gestureTap(cx - 36, y, CONTINUOUS_GESTURE_MS);
      gestureTap(cx + 36, y, CONTINUOUS_GESTURE_MS);
    }
    overlayTapX = cx;
    overlayTapY = top + (int) (h * 0.88f);
    dlog("OVERLAY_CARD_SPRAY reason=" + reason
        + " @" + overlayTapX + "," + overlayTapY
        + " card=" + card.width() + "x" + card.height());
  }

  private void scheduleContinuous() {
    if (continuousScheduled) return;
    if (!shouldRunContinuousSpray()) return;
    continuousScheduled = true;
    handler.postDelayed(continuousTapRunnable, CONTINUOUS_INTERVAL_MS);
  }

  private void stopContinuous(String reason) {
    handler.removeCallbacks(continuousTapRunnable);
    continuousScheduled = false;
    if (continuousTapCount > 0) {
      dlog("FG_CONTINUOUS_STOP reason=" + reason + " taps=" + continuousTapCount);
    }
    continuousTapCount = 0;
  }

  private void setRapidoForeground(boolean fg, String reason) {
    if (rapidoForeground == fg) {
      if (fg) {
        if (shouldRunContinuousSpray()) scheduleContinuous();
        else scheduleAcceptHuntPoll();
      } else if (!fg) {
        stopContinuous(reason + "/already-bg");
        stopAcceptHuntPoll(reason + "/already-bg");
        clearBlindSprayTargets(reason + "/already-bg");
      }
      return;
    }
    rapidoForeground = fg;
    dlog("RAPIDO_FOREGROUND=" + fg + " reason=" + reason);
    if (fg) {
      if (shouldRunContinuousSpray()) {
        scheduleContinuous();
      } else {
        scheduleAcceptHuntPoll();
      }
    } else {
      stopContinuous(reason);
      stopAcceptHuntPoll(reason);
      clearBlindSprayTargets(reason);
    }
  }

  private void setRideOverlayActive(boolean active, String reason) {
    if (rideOverlayActive == active) {
      // Overlay alone never starts continuous — Rapido FG only
      if (rapidoForeground && shouldRunContinuousSpray()) scheduleContinuous();
      return;
    }
    rideOverlayActive = active;
    dlog("RIDE_OVERLAY=" + active + " reason=" + reason
        + (active ? (" @" + overlayTapX + "," + overlayTapY) : ""));
    // Continuous spray is Rapido-FG-only; overlay flag only drives event race/hunt
    if (rapidoForeground && shouldRunContinuousSpray()) {
      scheduleContinuous();
    } else if (!rapidoForeground) {
      stopContinuous(reason + "/overlay-no-fg");
      if (!active) {
        overlayLiveUntilMs = 0;
        overlayCardBoundsValid = false;
        overlayCardBoundsRect.setEmpty();
      }
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

  /**
   * Detect Rapido FG + floating ride-alert overlays.
   * Prefer largest Accept-containing ride card; never the floating bubble icon.
   * Sticky overlay keeps card bounds for event-driven race/hunt only — never continuous spray.
   */
  private void refreshOverlayAndForeground(String reason) {
    String active = resolveActivePackage();
    if (active != null && active.equals(getPackageName())) {
      setRapidoForeground(false, reason + "/playnix");
      // Keep overlay flag if card still visible over Playnix, but don't spray
      OverlayHit hit = findBestOverlayWindow(active);
      if (hit != null) {
        markOverlayLive(hit.bounds, hit.tapX, hit.tapY);
        if (hit.pkg != null) lastPkg = hit.pkg;
        setRideOverlayActive(true, reason + "/over-playnix");
        hit.recycleRoot();
      }
      stopContinuous("playnix-fg");
      return;
    }
    if (active != null) {
      boolean isRapido = isRapidoPackageName(active);
      setRapidoForeground(isRapido, reason + "/fg=" + active);
      if (!isRapido) {
        // Strict: leaving Captain must kill continuous immediately (sticky overlay ≠ spray)
        stopContinuous(reason + "/left-rapido/" + active);
        clearBlindSprayTargets(reason + "/left-rapido");
      }
    }

    OverlayHit hit = findBestOverlayWindow(active);
      if (hit != null) {
        markOverlayLive(hit.bounds, hit.tapX, hit.tapY);
        if (hit.pkg != null) lastPkg = hit.pkg;
        if (hit.hasAccept && hit.tapX > 0 && hit.tapY > 0) {
          AutoClickerConfig.cacheOverlayTapPoint(hit.pkg, hit.tapX, hit.tapY);
        }
        setRideOverlayActive(true, reason);
        hit.recycleRoot();
      } else {
      // Sticky: keep hunt arm across transient miss — revoke live-tap auth
      long now = SystemClock.uptimeMillis();
      if (now < overlayStickyUntilMs && hasOverlayCardBounds()) {
        overlayLiveUntilMs = 0; // sticky ≠ live for absolute gestures
        setRideOverlayActive(true, reason + "/sticky");
      } else {
        overlayCardBoundsValid = false;
        overlayCardBoundsRect.setEmpty();
        overlayTapX = 0;
        overlayTapY = 0;
        overlayLiveUntilMs = 0;
        setRideOverlayActive(false, reason);
      }
    }
  }

  /** Floating ride card / system overlay — not fullscreen Captain and not the bubble icon. */
  private boolean isLikelyOverlayWindow(AccessibilityWindowInfo w) {
    if (w == null) return false;
    int type = w.getType();
    if (type == AccessibilityWindowInfo.TYPE_SYSTEM) {
      // Still reject icon-sized system windows
      try {
        w.getBoundsInScreen(scratchRect);
        if (isFloatingBubbleBounds(scratchRect)) return false;
      } catch (Exception ignored) {
      }
      return true;
    }
    try {
      w.getBoundsInScreen(scratchRect);
      if (scratchRect.isEmpty()) return false;
      if (isFloatingBubbleBounds(scratchRect)) return false;
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

  private static final class OverlayHit {
    final String pkg;
    final Rect bounds;
    final int tapX;
    final int tapY;
    final AccessibilityNodeInfo root;
    final AccessibilityNodeInfo acceptNode;
    final boolean hasAccept;

    OverlayHit(String pkg, Rect bounds, int tapX, int tapY,
        AccessibilityNodeInfo root, AccessibilityNodeInfo acceptNode) {
      this.pkg = pkg;
      this.bounds = bounds;
      this.tapX = tapX;
      this.tapY = tapY;
      this.root = root;
      this.acceptNode = acceptNode;
      this.hasAccept = acceptNode != null;
    }

    void recycleRoot() {
      if (acceptNode != null) {
        try { acceptNode.recycle(); } catch (Exception ignored) {}
      }
      if (root != null) {
        try { root.recycle(); } catch (Exception ignored) {}
      }
    }
  }

  /**
   * Best Rapido ride-offer overlay: prefer largest window with Accept label,
   * else largest ride-card-sized window with ₹/km/fare content.
   * NEVER selects the floating Rapido bubble / chat-head icon.
   */
  private OverlayHit findBestOverlayWindow(String activePkg) {
    OverlayHit bestAccept = null;
    int bestAcceptArea = -1;
    OverlayHit bestCard = null;
    int bestCardArea = -1;
    boolean rapidoFg = activePkg != null && AutoClickerConfig.isRapidoPackage(activePkg);

    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows == null) return null;
      for (AccessibilityWindowInfo w : windows) {
        if (w == null) continue;
        AccessibilityNodeInfo root = w.getRoot();
        if (root == null) continue;
        String p = packageOf(root);
        // Nuclear overlay spray is Rapido-only (Ola has its own path)
        if (p == null || !AutoClickerConfig.isRapidoPackage(p)) {
          root.recycle();
          continue;
        }

        Rect wb = new Rect();
        w.getBoundsInScreen(wb);
        if (wb.isEmpty() || isFloatingBubbleBounds(wb)) {
          dlog("OVERLAY_SKIP_BUBBLE pkg=" + p
              + " size=" + wb.width() + "x" + wb.height());
          root.recycle();
          continue;
        }

        boolean likely = isLikelyOverlayWindow(w);

        AccessibilityNodeInfo accept = findAcceptLabelInRoot(root);
        if (accept != null) {
          // While another app is FG, only Accept inside a real floating card
          if (!rapidoFg && !likely) {
            try { accept.recycle(); } catch (Exception ignored) {}
            root.recycle();
            continue;
          }
          if (!isValidAcceptOverlayBounds(wb) && !isRideCardSized(wb)) {
            dlog("OVERLAY_SKIP_TINY_ACCEPT pkg=" + p
                + " size=" + wb.width() + "x" + wb.height());
            try { accept.recycle(); } catch (Exception ignored) {}
            root.recycle();
            continue;
          }
          accept.getBoundsInScreen(scratchRect);
          int area = wb.width() * wb.height();
          OverlayHit hit = new OverlayHit(p, new Rect(wb),
              scratchRect.centerX(), scratchRect.centerY(), root, accept);
          // Prefer LARGEST Accept-containing ride card (not smallest / bubble)
          if (area > bestAcceptArea) {
            if (bestAccept != null) bestAccept.recycleRoot();
            bestAccept = hit;
            bestAcceptArea = area;
          } else {
            hit.recycleRoot();
          }
          continue;
        }

        if (!likely) {
          root.recycle();
          continue;
        }

        // No Accept: only ride-card sized windows with ride-like content
        if (!isRideCardSized(wb) || !rootLooksLikeRideCard(root)) {
          dlog("OVERLAY_SKIP_NOT_RIDE_CARD pkg=" + p
              + " size=" + wb.width() + "x" + wb.height());
          root.recycle();
          continue;
        }

        int area = wb.width() * wb.height();
        int tapX = wb.centerX();
        int tapY = wb.top + (int) (wb.height() * 0.88f);
        if (area > bestCardArea) {
          if (bestCard != null) bestCard.recycleRoot();
          bestCard = new OverlayHit(p, new Rect(wb), tapX, tapY, root, null);
          bestCardArea = area;
        } else {
          root.recycle();
        }
      }
    } catch (Exception ignored) {
    }

    OverlayHit chosen = bestAccept != null ? bestAccept : bestCard;
    if (bestAccept != null && bestCard != null) bestCard.recycleRoot();
    if (chosen != null) {
      dlog("OVERLAY_PICK pkg=" + chosen.pkg
          + " bounds=" + chosen.bounds.width() + "x" + chosen.bounds.height()
          + " hasAccept=" + chosen.hasAccept
          + " tap=@" + chosen.tapX + "," + chosen.tapY);
    }
    return chosen;
  }

  /**
   * Thin overlay Accept path: find Accept node → micro-burst smartClick.
   * No CTA band spray, no cached screen-bottom taps.
   */
  private void runOverlayRace(String hintPkg, long t0, String source) {
    if (!AutoClickerConfig.isEnabled()) return;
    if (olaBurstLock) return;
    if (isPlaynixForeground()) return;
    if (!canStartRapidoBurst()) return;

    String active = resolveActivePackage();
    OverlayHit hit = findBestOverlayWindow(active);
    dlog("OVERLAY_ACCEPT_HUNT source=" + source
        + " pkg=" + hintPkg
        + " found=" + (hit != null)
        + " hasAccept=" + (hit != null && hit.hasAccept)
        + " recv→start=" + (SystemClock.uptimeMillis() - t0) + "ms");

    if (hit == null) return;

    lastPkg = hit.pkg != null ? hit.pkg : hintPkg;
    markOverlayLive(hit.bounds, hit.tapX, hit.tapY);
    setRideOverlayActive(true, source);

    if (hit.acceptNode != null) {
      AccessibilityNodeInfo accept = AccessibilityNodeInfo.obtain(hit.acceptNode);
      AutoClickerConfig.cacheOverlayTapPoint(lastPkg, hit.tapX, hit.tapY);
      hit.recycleRoot();
      smartClickAccept(accept, lastPkg, "OVERLAY/" + source, t0);
      return;
    }

    // No Accept text — do NOT spray CTA / bubble / cached coords
    hit.recycleRoot();
    dlog("OVERLAY_SKIP no Accept node source=" + source);
  }

  private void startOverlayGestureBurst(Rect card, String pkg, long t0, String source) {
    if (card == null || card.isEmpty()) return;
    if (isFloatingBubbleBounds(card) || !isRideCardSized(card)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + pkg
          + " reason=overlay-burst-not-ride-card source=" + source
          + " size=" + card.width() + "x" + card.height());
      return;
    }
    int cx = card.centerX();
    int cy = card.top + (int) (card.height() * 0.88f);
    startOverlayGestureBurstAtPoint(cx, cy, pkg, t0, source);
  }

  private void startOverlayGestureBurstAtPoint(int x, int y, String pkg, long t0, String source) {
    if (overlayGestureBurstLock || x <= 0 || y <= 0) return;
    if (!canGestureAt(x, y)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + resolveActivePackage()
          + " reason=overlay-burst-gate @" + x + "," + y + " source=" + source);
      return;
    }
    overlayGestureBurstLock = true;
    dlog("OVERLAY_BURST source=" + source + " @" + x + "," + y
        + " count=" + RAPIDO_BURST_COUNT + " interval=" + RAPIDO_BURST_INTERVAL_MS + "ms");
    runOverlayGestureBurst(x, y, pkg, t0, 0);
  }

  private void runOverlayGestureBurst(int x, int y, String pkg, long t0, int index) {
    if (!AutoClickerConfig.isEnabled() || index >= RAPIDO_BURST_COUNT) {
      overlayGestureBurstLock = false;
      return;
    }
    // Abort mid-burst if user left Rapido / overlay died (e.g. opened Settings)
    if (!canGestureAt(x, y)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + resolveActivePackage()
          + " reason=overlay-burst-aborted i=" + index + " @" + x + "," + y);
      overlayGestureBurstLock = false;
      return;
    }
    gestureTap(x, y, RAPIDO_GESTURE_MS);
    gestureTap(x, y, RAPIDO_GESTURE_MS);
    gestureTap(x - 30, y, RAPIDO_GESTURE_MS);
    gestureTap(x + 30, y, RAPIDO_GESTURE_MS);
    if (index == 0) {
      emit(pkg, "OverlayBurst @" + x + "," + y, (int) (SystemClock.uptimeMillis() - t0));
    }
    handler.postDelayed(
        () -> runOverlayGestureBurst(x, y, pkg, t0, index + 1),
        RAPIDO_BURST_INTERVAL_MS
    );
  }

  private void arm(String reason) {
    raceArmedUntilMs = SystemClock.uptimeMillis() + RACE_ARM_MS;
    dlog("ARMED reason=" + reason + " ms=" + RACE_ARM_MS);
    // Arm enables thin overlay Accept hunt — continuous only if explicit pref + Rapido FG
    if (shouldRunContinuousSpray()) scheduleContinuous();
  }

  /**
   * NLS / a11y notification: Accept PendingIntent first, contentIntent,
   * then thin Accept hunt (0/2/5/10/18/30ms). No continuous, no overlay CTA spray.
   */
  public static void onRideSignal(
      String packageName,
      Notification notification,
      String notifText,
      long tReceive,
      String source
  ) {
    final AutoClickerService svc = sInstance;
    if (!AutoClickerConfig.isEnabled()) return;
    if (packageName == null || !AutoClickerConfig.isPackageMonitored(packageName)) return;
    if (isSpamText(notifText)) return;

    // 1) Accept PendingIntent FIRST (Playnix edge)
    boolean actionOk = tryFireAcceptPendingIntent(notification, tReceive, source);

    // 2) contentIntent OK
    if (notification != null && notification.contentIntent != null) {
      try {
        notification.contentIntent.send();
      } catch (Exception ignored) {
      }
    }

    if (svc == null) return;
    svc.lastPkg = packageName;
    svc.raceArmedUntilMs = SystemClock.uptimeMillis() + RACE_ARM_MS;

    Runnable race = () -> {
      svc.arm(source);
      dlog("RIDE_PING source=" + source + " pkg=" + packageName
          + " actionOk=" + actionOk
          + " recv→arm=" + (SystemClock.uptimeMillis() - tReceive) + "ms");
      // Immediate Accept-hunt on Rapido windows only — no overlay race spray
      svc.huntAcceptAnywhere(packageName, tReceive, "NlsHunt+0");
      // Follow-ups at 2/5/10/18/30ms — Accept-hunt only
      svc.scheduleNlsFollowups(packageName, tReceive);
      // Do NOT scheduleContinuous / runOverlayRace / routePackage here
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
      long tReceive,
      String source
  ) {
    if (n == null || !AutoClickerConfig.isEnabled()) return false;
    Notification.Action action = findAcceptAction(n);
    if (action == null || action.actionIntent == null) return false;
    AutoClickerService svc = sInstance;
    try {
      action.actionIntent.send();
      long ms = SystemClock.uptimeMillis() - tReceive;
      String title = action.title != null ? action.title.toString() : "";
      dlog("ACCEPT_ACTION_OK source=" + source
          + " recv→send=" + ms + "ms title=[" + title + "]");
      if (svc != null) {
        svc.arm(source + "/action");
        svc.emit(svc.lastPkg, "AcceptAction/" + source + " " + title, (int) ms);
      }
      return true;
    } catch (Exception e) {
      Log.w(TAG, "ACCEPT_ACTION_FAIL", e);
      return false;
    }
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
    if (containsIgnoreCase(title, "accepted") || containsIgnoreCase(title, "reject")
        || containsIgnoreCase(title, "decline") || containsIgnoreCase(title, "cancel")
        || containsIgnoreCase(title, "dismiss")) {
      return false;
    }
    if (indexOfSeq(title, "स्वीकार") >= 0) return true;
    if (equalsIgnoreCaseTrim(title, "accept")) return true;
    if (startsWithIgnoreCase(title, "accept ")) return true;
    return containsIgnoreCase(title, "accept ride")
        || containsIgnoreCase(title, "accept order")
        || containsIgnoreCase(title, "accept booking")
        || containsIgnoreCase(title, "accept trip")
        || containsIgnoreCase(title, "take ride");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    final long t0 = SystemClock.uptimeMillis();
    if (event == null) return;

    if (!AutoClickerConfig.isEnabled()) {
      stopContinuous("master-off");
      stopAcceptHuntPoll("master-off");
      return;
    }

    // While user is in Playnix settings UI — skip race work (fixes touch lag)
    CharSequence pkgCsEarly = event.getPackageName();
    String pkgEarly = pkgCsEarly != null ? pkgCsEarly.toString() : "";
    if (pkgEarly.equals(getPackageName()) || isPlaynixForeground()) {
      stopContinuous("playnix-event");
      stopAcceptHuntPoll("playnix-event");
      return;
    }

    long now = SystemClock.uptimeMillis();
    if (now - lastHeartbeatMs >= HEARTBEAT_MS) {
      lastHeartbeatMs = now;
      Log.i(TAG, "Scanning... fgRapido=" + rapidoForeground
          + " overlay=" + rideOverlayActive
          + " continuous=" + continuousScheduled
          + " acceptPoll=" + acceptHuntPollScheduled
          + " (System Healthy)");
    }

    CharSequence pkgCs = event.getPackageName();
    String pkg = pkgCs != null ? pkgCs.toString() : "";
    int type = event.getEventType();

    if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
      if (!AutoClickerConfig.isPackageMonitored(pkg)) return;
      String text = notifText(event);
      if (isSpamText(text)) return;
      lastPkg = pkg;
      Parcelable data = event.getParcelableData();
      Notification n = data instanceof Notification ? (Notification) data : null;
      onRideSignal(pkg, n, text, t0, "A11yNotif");
      return;
    }

    if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      return;
    }

    boolean isRapido = isRapidoPackageName(pkg);
    boolean isOla = AutoClickerConfig.isOlaPackage(pkg);
    boolean windowsChanged = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

    // Strict package router: non-Ola / non-Rapido → return immediately
    // Exception: thin overlay Accept hunt when NLS-armed OR WINDOWS_CHANGED
    if (!isRapido && !isOla) {
      if (windowsChanged) {
        String active = resolveActivePackage();
        if (active != null && !isRapidoPackageName(active)) {
          setRapidoForeground(false, "left/" + active);
        }
      }
      if (isRaceArmed() || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
        if (!isPlaynixForeground()) {
          // Accept on Rapido windows only — no CTA spray / continuous
          huntAcceptAnywhere(lastPkg, t0, "ThinOverlay");
        }
      }
      return;
    }

    lastPkg = pkg;

    // Light FG flag — do NOT refreshOverlay / findBestOverlay before first Accept
    if (isRapido) {
      setRapidoForeground(true, "event-pkg");
    } else if (isOla) {
      setRapidoForeground(false, "ola-event");
    }

    // Single ladder: routePackage → handleRapido/handleOla (micro-burst smartClick)
    // Do NOT also runOverlayRace + continuous + 20× burst on the same event
    AccessibilityNodeInfo source = null;
    try {
      source = event.getSource();
      AccessibilityNodeInfo root = source != null ? source : safeRapidoOrOlaRoot(pkg);
      if (root == null) return;
      String rootPkg = packageOf(root);
      if (rootPkg == null
          || (!isRapidoPackageName(rootPkg) && !AutoClickerConfig.isOlaPackage(rootPkg))) {
        Log.w(TAG, "BLOCKED_TAP pkg=" + rootPkg
            + " reason=route-root-not-ride-app eventPkg=" + pkg);
        if (source == null) root.recycle();
        return;
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

    if (shouldRunContinuousSpray()) {
      scheduleContinuous();
    } else if (rapidoForeground) {
      scheduleAcceptHuntPoll();
    }
  }

  /** Called from JS bridge when master/nuclear/continuous toggles change. */
  public static void onConfigChanged() {
    final AutoClickerService svc = sInstance;
    if (svc == null) return;
    svc.handler.post(() -> {
      if (!svc.shouldRunContinuousSpray()) {
        svc.stopContinuous("config");
        if (svc.rapidoForeground) svc.scheduleAcceptHuntPoll();
        else svc.stopAcceptHuntPoll("config");
      } else {
        svc.stopAcceptHuntPoll("config-continuous");
        svc.scheduleContinuous();
      }
    });
  }

  private void routePackage(String pkg, AccessibilityNodeInfo root, long t0) {
    if (AutoClickerConfig.isOlaPackage(pkg)) {
      handleOla(root, t0, pkg);
    } else if (AutoClickerConfig.isRapidoPackage(pkg)
        || pkg.contains("rideandhra")) {
      handleRapido(root, t0, pkg);
    }
    // Uber: stub (MeClicker also no-op)
  }

  /**
   * Find Accept anywhere — Rapido floating ride cards first, then Rapido FG window.
   * Does not gesture-spray Accept coords from a background fullscreen Rapido while
   * the user is in Chrome/WhatsApp/etc. (node click on a true overlay card is OK).
   */
  private boolean huntAcceptAnywhere(String hintPkg, long t0, String source) {
    if (!AutoClickerConfig.isEnabled()) return false;
    if (olaBurstLock) return false;
    if (isPlaynixForeground()) return false;
    // Soft burst-gap / post-hit CD — never a multi-second dead zone after a miss
    if (!canStartRapidoBurst()) return false;
    // Hot sources (FG / overlay / event / NLS): zero debounce — Accept→click ASAP
    long now = SystemClock.uptimeMillis();
    long debounce = isHotHuntSource(source) ? HUNT_DEBOUNCE_MS : HUNT_DEBOUNCE_COLD_MS;
    if (debounce > 0 && now - lastHuntAtMs < debounce) return false;

    AccessibilityNodeInfo hit = null;
    String hitPkg = hintPkg;
    boolean fromOverlay = false;
    String activePkg = resolveActivePackage();
    boolean rapidoFg = activePkg != null && AutoClickerConfig.isRapidoPackage(activePkg);

    // 1) Floating Rapido ride-alert windows FIRST (skip bubble / icon windows)
    try {
      List<AccessibilityWindowInfo> windows = getWindows();
      if (windows != null) {
        for (AccessibilityWindowInfo w : windows) {
          if (w == null) continue;
          AccessibilityNodeInfo root = w.getRoot();
          if (root == null) continue;
          try {
            String p = packageOf(root);
            if (p == null || !AutoClickerConfig.isRapidoPackage(p)) continue;
            try {
              w.getBoundsInScreen(scratchRect2);
              if (isFloatingBubbleBounds(scratchRect2)) continue;
            } catch (Exception ignored) {
            }
            boolean prefer = isLikelyOverlayWindow(w);
            // While another app is FG, only hunt Accept inside a real floating card
            if (!rapidoFg && !prefer) continue;
            AccessibilityNodeInfo candidate = findAcceptLabelInRoot(root);
            if (candidate != null) {
              if (hit != null) hit.recycle();
              hit = candidate;
              hitPkg = p;
              fromOverlay = prefer;
              if (prefer) break;
            }
          } finally {
            root.recycle();
          }
        }
      }
    } catch (Exception ignored) {
    }

    // 2) Active window (Rapido FG only — avoid spraying other apps)
    if (hit == null && rapidoFg) {
      AccessibilityNodeInfo active = null;
      try {
        active = getRootInActiveWindow();
        if (active != null) {
          String p = packageOf(active);
          if (p != null && AutoClickerConfig.isRapidoPackage(p)) {
            hit = findAcceptLabelInRoot(active);
            if (hit != null) hitPkg = p;
          }
        }
      } catch (Exception ignored) {
      } finally {
        if (active != null) active.recycle();
      }
    }

    // 3) Remaining Rapido windows only when Rapido is FG
    if (hit == null && rapidoFg) {
      try {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
          for (AccessibilityWindowInfo w : windows) {
            if (w == null || isLikelyOverlayWindow(w)) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            try {
              String p = packageOf(root);
              if (p == null || !AutoClickerConfig.isRapidoPackage(p)) continue;
              hit = findAcceptLabelInRoot(root);
              if (hit != null) {
                hitPkg = p;
                break;
              }
            } finally {
              root.recycle();
            }
          }
        }
      } catch (Exception ignored) {
      }
    }

    if (hit == null) return false;

    if (!AutoClickerConfig.isNuclearMode() && AutoClickerConfig.getMinPrice() > 0) {
      AccessibilityNodeInfo dumpRoot = safeRoot();
      if (dumpRoot != null) {
        try {
          String dump = dumpText(dumpRoot);
          double price = parseRapidoPrice(dump);
          if (price > 0 && price < AutoClickerConfig.getMinPrice()) {
            dlog("HUNT_SKIP price=" + price + " < min");
            hit.recycle();
            return false;
          }
        } finally {
          dumpRoot.recycle();
        }
      }
    }

    lastHuntAtMs = SystemClock.uptimeMillis();
    hit.getBoundsInScreen(scratchRect);
    int cx = scratchRect.centerX();
    int cy = scratchRect.centerY();
    if (fromOverlay) {
      if (hasOverlayCardBounds()) {
        scratchRect2.set(overlayCardBoundsRect);
      } else {
        scratchRect2.set(scratchRect.left - 48, scratchRect.top - 120,
            scratchRect.right + 48, scratchRect.bottom + 24);
      }
      if (!scratchRect2.contains(cx, cy)) {
        scratchRect2.union(scratchRect);
      }
      markOverlayLive(scratchRect2, cx, cy);
      AutoClickerConfig.cacheOverlayTapPoint(hitPkg != null ? hitPkg : lastPkg, overlayTapX, overlayTapY);
      setRideOverlayActive(true, "hunt-hit");
    } else {
      AutoClickerConfig.cacheTapPoint(hitPkg != null ? hitPkg : lastPkg, cx, cy);
    }
    lastPkg = hitPkg != null ? hitPkg : lastPkg;
    dlog("HUNT_ACCEPT source=" + source
        + " pkg=" + hitPkg
        + " overlay=" + fromOverlay
        + " @" + cx + "," + cy
        + " findMs=" + (SystemClock.uptimeMillis() - t0));
    // Micro-burst smartClick — not a single tap + multi-second rest
    smartClickAccept(hit, lastPkg,
        fromOverlay ? "OverlayHunt/" + source : "Hunt/" + source, t0);
    arm("hunt-hit");
    return true;
  }

  private static boolean isHotHuntSource(String source) {
    if (source == null) return false;
    // Avoid toLowerCase alloc — check common hot prefixes/substrings case-insensitively
    return containsIgnoreCase(source, "fg")
        || containsIgnoreCase(source, "overlay")
        || containsIgnoreCase(source, "event")
        || containsIgnoreCase(source, "fast")
        || containsIgnoreCase(source, "nls")
        || containsIgnoreCase(source, "hunt")
        || containsIgnoreCase(source, "content")
        || containsIgnoreCase(source, "window");
  }

  private static String packageOf(AccessibilityNodeInfo node) {
    if (node == null) return null;
    CharSequence p = node.getPackageName();
    return p != null ? p.toString() : null;
  }

  /** Find real Accept label anywhere in this tree (any screen position). */
  private AccessibilityNodeInfo findAcceptLabelInRoot(AccessibilityNodeInfo root) {
    if (root == null) return null;

    for (String search : ACCEPT_LABELS) {
      List<AccessibilityNodeInfo> nodes;
      try {
        nodes = root.findAccessibilityNodeInfosByText(search);
      } catch (Exception e) {
        continue;
      }
      if (nodes == null) continue;
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
        found = node;
      }
      if (found != null) return found;
    }

    // Fallback BFS on contentDescription (some buttons have no getText)
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < TREE_WALK_CAP) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      if (isRealAcceptLabel(nodeTextCs(n))) {
        n.getBoundsInScreen(scratchRect);
        if (!scratchRect.isEmpty() && scratchRect.width() >= 24) {
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
    if (text == null) return false;
    int len = text.length();
    if (len == 0 || len > 48) return false;
    if (containsIgnoreCase(text, "accepted") || containsIgnoreCase(text, "reject")
        || containsIgnoreCase(text, "decline") || containsIgnoreCase(text, "cancel")
        || containsIgnoreCase(text, "not accept")) {
      return false;
    }
    if (indexOfSeq(text, "स्वीकार") >= 0) return true;
    if (containsIgnoreCase(text, "accept")) return true;
    return equalsIgnoreCaseTrim(text, "take ride")
        || equalsIgnoreCaseTrim(text, "take order");
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

  // ─── Ola (MeClicker i) ────────────────────────────────────────────────────

  private void handleOla(AccessibilityNodeInfo root, long t0, String pkg) {
    long now = SystemClock.uptimeMillis();
    if (now - lastOlaAttemptMs < COOLDOWN_MS) return;
    if (olaBurstLock) return;

    AccessibilityNodeInfo acceptBtn = null;
    double matchedPrice = 0;

    if (AutoClickerConfig.getFilterMode() == AutoClickerConfig.MODE_PRICE) {
      List<AccessibilityNodeInfo> prices = root.findAccessibilityNodeInfosByViewId(OLA_PRICE_ID);
      if (prices == null || prices.isEmpty()) {
        // Also try legacy package id prefix
        prices = root.findAccessibilityNodeInfosByViewId("com.olacabs.driver:id/tv_compact_price");
      }
      AccessibilityNodeInfo best = null;
      double bestPrice = -1;
      if (prices != null) {
        for (AccessibilityNodeInfo n : prices) {
          String text = nodeText(n);
          if (text == null || text.contains("/")) {
            n.recycle();
            continue;
          }
          double p = parseLooseDouble(text);
          if (p > bestPrice) {
            if (best != null) best.recycle();
            best = n;
            bestPrice = p;
          } else {
            n.recycle();
          }
        }
      }
      if (best == null) return;
      matchedPrice = bestPrice;
      int min = AutoClickerConfig.getMinPrice();
      if (matchedPrice < min) {
        dlog("OLA_SKIP price=" + matchedPrice + " < min=" + min);
        best.recycle();
        return;
      }
      acceptBtn = findOlaAcceptNear(best);
      best.recycle();
    } else {
      List<AccessibilityNodeInfo> pickups = root.findAccessibilityNodeInfosByViewId(OLA_PICKUP_ID);
      List<AccessibilityNodeInfo> drops = root.findAccessibilityNodeInfosByViewId(OLA_DROP_ID);
      if (pickups == null) pickups = new ArrayList<>();
      if (drops == null) drops = new ArrayList<>();
      int n = Math.min(pickups.size(), drops.size());
      AccessibilityNodeInfo winPickup = null;
      for (int i = 0; i < n; i++) {
        float pk = parseFirstNumber(nodeText(pickups.get(i)));
        float dk = parseFirstNumber(nodeText(drops.get(i)));
        if (pk <= AutoClickerConfig.getMaxPickup()
            && (AutoClickerConfig.getMaxDrop() == 0f || dk >= AutoClickerConfig.getMaxDrop())) {
          winPickup = AccessibilityNodeInfo.obtain(pickups.get(i));
          break;
        }
      }
      recycleAll(pickups);
      recycleAll(drops);
      if (winPickup == null) return;
      acceptBtn = findOlaAcceptNear(winPickup);
      winPickup.recycle();
    }

    if (acceptBtn == null) return;

    Rect b = new Rect();
    acceptBtn.getBoundsInScreen(b);
    if (b.isEmpty()) {
      acceptBtn.recycle();
      return;
    }
    final int cx = b.centerX();
    final int cy = b.centerY();
    AutoClickerConfig.cacheTapPoint(pkg, cx, cy);

    lastOlaAttemptMs = SystemClock.uptimeMillis();
    olaBurstLock = true;
    long elapsed = SystemClock.uptimeMillis() - t0;
    long wait = Math.max(0, OLA_ACCEPT_WINDOW_MS - elapsed);
    dlog("OLA_LOCK @" + cx + "," + cy
        + " price=" + matchedPrice
        + " | T:" + OLA_GESTURE_MS + "ms G:" + OLA_BURST_INTERVAL_MS
        + "ms A:" + OLA_ACCEPT_WINDOW_MS + "ms wait=" + wait + "ms");

    final AccessibilityNodeInfo locked = acceptBtn;
    handler.postDelayed(() -> runOlaBurst(locked, cx, cy, pkg, t0, 0), wait);
  }

  private AccessibilityNodeInfo findOlaAcceptNear(AccessibilityNodeInfo from) {
    AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(from);
    for (int d = 0; d < PARENT_CLIMB_OLA && cur != null; d++) {
      List<AccessibilityNodeInfo> btns = cur.findAccessibilityNodeInfosByViewId(OLA_ACCEPT_ID);
      if (btns == null || btns.isEmpty()) {
        btns = cur.findAccessibilityNodeInfosByViewId("com.olacabs.driver:id/btn_accept");
      }
      if (btns != null && !btns.isEmpty()) {
        AccessibilityNodeInfo hit = btns.get(0);
        for (int i = 1; i < btns.size(); i++) btns.get(i).recycle();
        cur.recycle();
        return hit;
      }
      AccessibilityNodeInfo parent = cur.getParent();
      cur.recycle();
      cur = parent;
    }
    if (cur != null) cur.recycle();
    return null;
  }

  private void runOlaBurst(
      AccessibilityNodeInfo locked,
      int cx,
      int cy,
      String pkg,
      long t0,
      int index
  ) {
    if (!AutoClickerConfig.isEnabled()) {
      finishOlaBurst(locked);
      return;
    }
    if (index >= OLA_BURST_COUNT) {
      finishOlaBurst(locked);
      return;
    }

    boolean nodeOk = false;
    try {
      if (locked != null && locked.refresh()) {
        nodeOk = locked.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
    } catch (Exception e) {
      Log.w(TAG, "OLA Target Lost/Taken! Retargeting...");
    }

    boolean gestOk = gestureTap(cx, cy, OLA_GESTURE_MS);
    if (!gestOk && !nodeOk && index == 0) {
      Log.w(TAG, "OLA_BURST first tap weak");
    }

    if (index == 0) {
      int ms = (int) (SystemClock.uptimeMillis() - t0);
      emit(pkg, "OlaBurst @" + cx + "," + cy, ms);
    }

    final int next = index + 1;
    handler.postDelayed(
        () -> runOlaBurst(locked, cx, cy, pkg, t0, next),
        OLA_BURST_INTERVAL_MS
    );
  }

  private void finishOlaBurst(AccessibilityNodeInfo locked) {
    olaBurstLock = false;
    if (locked != null) {
      try {
        locked.recycle();
      } catch (Exception ignored) {
      }
    }
  }

  // ─── Rapido (micro-burst race) ────────────────────────────────────────────

  private void handleRapido(AccessibilityNodeInfo root, long t0, String pkg) {
    if (!canStartRapidoBurst()) return;
    if (isPlaynixForeground()) return;

    boolean nuclear = AutoClickerConfig.isNuclearMode();

    // Nuclear: Accept-first, soft filters — NOT continuous spray
    if (nuclear) {
      AccessibilityNodeInfo accept = findAcceptLabelInRoot(root);
      if (accept == null) {
        accept = bfsFindAccept(root, true);
      }
      if (accept == null) {
        accept = bfsFindAccept(root, false);
      }
      if (accept != null) {
        smartClickAccept(accept, pkg, "Rapido-Nuclear", t0);
      }
      // No Accept — do NOT spray CTA / start continuous
      return;
    }

    // Standard: dump text + early reject + filters + micro-burst smartClick
    String dump = dumpText(root);
    if (dump.isEmpty()) return;

    String lower = dump.toLowerCase(Locale.US);
    if (lower.contains("accepted") || lower.contains("completed") || lower.contains("cancelled")) {
      return;
    }
    if (!lower.contains("accept") && !dump.contains("स्वीकार")) return;

    boolean moneyOrKm = dump.contains("₹") || lower.contains("rs") || lower.contains("cash")
        || lower.contains("km") || lower.contains("fare");
    if (!moneyOrKm) return;
    if (AutoClickerConfig.getFilterMode() == AutoClickerConfig.MODE_PRICE) {
      double price = parseRapidoPrice(dump);
      int min = AutoClickerConfig.getMinPrice();
      if (price <= 0 || price < min) {
        dlog("RAPIDO_SKIP price=" + price + " min=" + min);
        return;
      }
    } else {
      List<Float> kms = parseAllKm(dump);
      if (kms.size() < 2) return;
      if (kms.get(0) > AutoClickerConfig.getMaxPickup()) return;
      if (AutoClickerConfig.getMaxDrop() != 0f && kms.get(1) < AutoClickerConfig.getMaxDrop()) return;
    }

    AccessibilityNodeInfo accept = bfsFindAccept(root, true);
    if (accept == null) {
      accept = bfsFindAccept(root, false);
    }
    if (accept == null) return;

    smartClickAccept(accept, pkg, "Rapido", t0);
  }

  private String dumpText(AccessibilityNodeInfo root) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    ArrayDeque<AccessibilityNodeInfo> q = scratchQueue;
    q.clear();
    q.add(AccessibilityNodeInfo.obtain(root));
    int walked = 0;
    while (!q.isEmpty() && walked < TREE_WALK_CAP) {
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
    while (!q.isEmpty() && walked < TREE_WALK_CAP) {
      AccessibilityNodeInfo n = q.removeFirst();
      walked++;
      CharSequence text = nodeTextCs(n);
      boolean isAccept = text != null && (
          containsIgnoreCase(text, "accept") || indexOfSeq(text, "स्वीकार") >= 0
      );
      if (isAccept && (!clickableOnly || n.isClickable())) {
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
   * Accept hit → immediate dual/triple strike + micro-burst extras @~5ms.
   * Soft retry window (~250ms) for re-bursts; post-hit CD only after window.
   * Takes ownership of {@code node} (recycles it / holds for burst then recycles).
   */
  private boolean smartClickAccept(AccessibilityNodeInfo node, String pkg, String tag, long t0) {
    if (node == null) return false;
    if (!canStartRapidoBurst()) {
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }
    if (!nodeIsRapido(node)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + packageOf(node) + " reason=smart-non-rapido-node tag=" + tag);
      try { node.recycle(); } catch (Exception ignored) {}
      return false;
    }

    AccessibilityNodeInfo clickTarget = climbToClickable(node);
    if (clickTarget == null) {
      clickTarget = AccessibilityNodeInfo.obtain(node);
    }
    node.recycle();

    if (!nodeIsRapido(clickTarget)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + packageOf(clickTarget)
          + " reason=smart-click-target-non-rapido tag=" + tag);
      try { clickTarget.recycle(); } catch (Exception ignored) {}
      return false;
    }

    clickTarget.getBoundsInScreen(scratchRect);
    final int cx = scratchRect.centerX();
    final int cy = scratchRect.centerY();
    if (cx > 0 && cy > 0) {
      AutoClickerConfig.cacheTapPoint(pkg, cx, cy);
      if (!rapidoForeground) {
        if (hasOverlayCardBounds()) {
          scratchRect2.set(overlayCardBoundsRect);
        } else {
          scratchRect2.set(scratchRect);
        }
        if (!scratchRect2.contains(cx, cy)) scratchRect2.union(scratchRect);
        markOverlayLive(scratchRect2, cx, cy);
        setRideOverlayActive(true, "smart-" + tag);
      }
    }

    long now = SystemClock.uptimeMillis();
    // Open / extend soft retry window; do NOT stamp multi-second CD here
    if (now >= rapidoRetryUntilMs) {
      rapidoRetryUntilMs = now + RAPIDO_RETRY_WINDOW_MS;
      handler.removeCallbacks(rapidoRetryWindowEndRunnable);
      handler.postDelayed(rapidoRetryWindowEndRunnable, RAPIDO_RETRY_WINDOW_MS);
    }
    lastRapidoAttemptMs = now;
    rapidoBurstLock = true;
    // Cancel any prior extra-strike chain
    handler.removeCallbacks(rapidoMicroBurstRunnable);
    if (rapidoBurstNode != null && rapidoBurstNode != clickTarget) {
      try { rapidoBurstNode.recycle(); } catch (Exception ignored) {}
    }
    rapidoBurstNode = clickTarget;
    rapidoBurstX = cx;
    rapidoBurstY = cy;
    rapidoBurstPkg = pkg;
    rapidoBurstTag = tag;
    rapidoBurstT0 = t0;
    rapidoBurstIndex = 0;

    long clickAt = SystemClock.uptimeMillis();
    // Immediate dual strike (ACTION_CLICK + gesture)
    boolean ok = dualStrikeAccept(clickTarget, cx, cy);
    // Triple: one more gesture immediately (race hammer, still <10ms path)
    if (cx > 0 && cy > 0) {
      gestureTap(cx, cy, RAPIDO_GESTURE_MS);
    }
    rapidoBurstFirstOk = ok;

    long t0ToClick = clickAt - t0;
    dlog("SMART_CLICK tag=" + tag
        + " ok=" + ok
        + " t0→click=" + t0ToClick + "ms"
        + " microExtra=" + RAPIDO_MICRO_EXTRA_STRIKES
        + " @" + cx + "," + cy);
    if (ok) {
      emit(pkg, tag + " @" + cx + "," + cy, (int) t0ToClick);
    }

    // 4–8 more dual strikes at ~5ms — beat competitors without forever spray
    handler.postDelayed(rapidoMicroBurstRunnable, RAPIDO_MICRO_INTERVAL_MS);
    return ok;
  }

  /** Sync ACTION_CLICK (Rapido node only) + short gesture at Accept center. */
  private boolean dualStrikeAccept(AccessibilityNodeInfo clickTarget, int cx, int cy) {
    boolean clicked = false;
    if (clickTarget != null) {
      try {
        if (clickTarget.isClickable() && clickTarget.refresh() && nodeIsRapido(clickTarget)) {
          clicked = performRapidoClick(clickTarget);
        }
      } catch (Exception ignored) {
      }
    }
    boolean gestOk = false;
    if (cx > 0 && cy > 0) {
      gestOk = gestureTap(cx, cy, RAPIDO_GESTURE_MS);
    }
    return clicked || gestOk;
  }

  private void finishRapidoMicroBurst(String reason) {
    handler.removeCallbacks(rapidoMicroBurstRunnable);
    long now = SystemClock.uptimeMillis();
    rapidoBurstLock = false;
    if (rapidoBurstNode != null) {
      try { rapidoBurstNode.recycle(); } catch (Exception ignored) {}
      rapidoBurstNode = null;
    }
    // Longer CD only after soft retry window ends (or destroy) — not after first miss
    if (now >= rapidoRetryUntilMs || "destroy".equals(reason)) {
      rapidoCooldownUntilMs = now + RAPIDO_POST_HIT_COOLDOWN_MS;
      dlog("RAPIDO_POST_HIT_CD reason=" + reason + " cd=" + RAPIDO_POST_HIT_COOLDOWN_MS + "ms");
    } else {
      dlog("RAPIDO_MICRO_DONE reason=" + reason
          + " tag=" + rapidoBurstTag
          + " firstOk=" + rapidoBurstFirstOk
          + " retryLeft=" + (rapidoRetryUntilMs - now) + "ms");
    }
    rapidoBurstPkg = null;
    rapidoBurstTag = null;
    rapidoBurstT0 = 0;
  }

  /** Alias kept for residual callers — delegates to smartClickAccept. */
  private void startRapidoBurst(AccessibilityNodeInfo node, String pkg, String tag, long t0) {
    smartClickAccept(node, pkg, tag, t0);
  }

  private AccessibilityNodeInfo climbToClickable(AccessibilityNodeInfo node) {
    AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
    for (int d = 0; d < PARENT_CLIMB_CLICK && cur != null; d++) {
      if (cur.isClickable()) return cur;
      AccessibilityNodeInfo parent = cur.getParent();
      cur.recycle();
      cur = parent;
    }
    if (cur != null) cur.recycle();
    return null;
  }

  private void finishRapidoBurst(AccessibilityNodeInfo locked) {
    finishRapidoMicroBurst("legacy");
    if (locked != null) {
      try {
        locked.recycle();
      } catch (Exception ignored) {
      }
    }
  }

  // ─── Parsing ──────────────────────────────────────────────────────────────

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

  private static List<Float> parseAllKm(String dump) {
    List<Float> out = new ArrayList<>();
    Matcher m = KM_PATTERN.matcher(dump);
    while (m.find()) {
      out.add((float) parseLooseDouble(m.group(1)));
    }
    return out;
  }

  private static float parseFirstNumber(String text) {
    if (text == null) return 0f;
    Matcher m = FIRST_NUMBER.matcher(text);
    if (m.find()) return (float) parseLooseDouble(m.group(1));
    return 0f;
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
   * Hard-gated absolute tap. Rapido FG → any point OK.
   * Else only if (x,y) is inside a *live* Rapido overlay card.
   * Ola FG allowed for Ola burst path. Logs BLOCKED_TAP when refused.
   */
  private boolean gestureTap(int x, int y, long durationMs) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;
    if (x <= 0 || y <= 0) return false;
    if (!canGestureAt(x, y)) {
      Log.w(TAG, "BLOCKED_TAP pkg=" + resolveActivePackage()
          + " reason=gesture-gate fgRapido=" + rapidoForeground
          + " liveOverlay=" + hasLiveRapidoOverlayCard()
          + " @" + x + "," + y);
      return false;
    }
    // Path reused; Builder/StrokeDescription must be new per API contract
    scratchPath.rewind();
    scratchPath.moveTo(x, y);
    return dispatchGesture(
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(
                scratchPath, 0, Math.max(1, durationMs)))
            .build(),
        null,
        null
    );
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
          AccessibilityNodeInfo r = w.getRoot();
          if (r == null) continue;
          String p = packageOf(r);
          if (p == null || !isRapidoPackageName(p)) {
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

  private AccessibilityNodeInfo safeRapidoOrOlaRoot(String hintPkg) {
    if (hintPkg != null && isRapidoPackageName(hintPkg)) {
      AccessibilityNodeInfo r = findRapidoRoot();
      if (r != null) return r;
    }
    if (hintPkg != null && AutoClickerConfig.isOlaPackage(hintPkg)) {
      try {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
          String p = packageOf(active);
          if (p != null && AutoClickerConfig.isOlaPackage(p)) return active;
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
        if (p != null && (isRapidoPackageName(p) || AutoClickerConfig.isOlaPackage(p))) {
          return active;
        }
        active.recycle();
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
    if (nodes == null) return;
    for (AccessibilityNodeInfo n : nodes) {
      if (n != null) n.recycle();
    }
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

  static boolean isSpamText(String hay) {
    if (hay == null || hay.isEmpty()) return false;
    // Never spam-filter if it looks like a ride offer
    if (hay.contains("accept") || hay.contains("₹") || hay.contains(" km")
        || hay.contains("pickup") || hay.contains("new order") || hay.contains("new ride")) {
      return false;
    }
    return hay.contains("completed order") || hay.contains("total earning")
        || hay.contains("accepted orders") || hay.contains("login") || hay.contains("otp")
        || hay.contains("password") || hay.contains("wallet") || hay.contains("cashout")
        || hay.contains("payout") || hay.contains("rating") || hay.contains("rate your")
        || hay.contains("update available") || hay.contains("battery")
        || hay.contains("document") || hay.contains("training");
  }

  private void emit(String packageName, String label, int latencyMs) {
    long now = SystemClock.uptimeMillis();
    if (now - lastEmitAtMs < 350) return;
    lastEmitAtMs = now;
    vibrate();
    AutoClickerModule.emitRideAccepted(
        packageName, 0, label, latencyMs,
        AutoClickerConfig.isNuclearMode() ? "Nuclear" : "Standard"
    );
  }

  private void vibrate() {
    Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
    if (v == null || !v.hasVibrator()) return;
    v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
  }

  @Override
  public void onInterrupt() {
    Log.w(TAG, "interrupted");
  }
}
