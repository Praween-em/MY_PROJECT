package com.ridio.app;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Locale;

/**
 * Earliest ride ping. Only forwards real ride alerts — never status/earnings pings.
 */
public class RideAlertListener extends NotificationListenerService {

  private static final String TAG = "RideAlertListener";
  private static final long SKIP_LOG_MIN_INTERVAL_MS = 1500L;

  private final StringBuilder scratchSb = new StringBuilder(256);
  private long lastSkipDisabledLogUptime = 0L;
  private long lastSkipFilterLogUptime = 0L;

  @Override
  public void onListenerConnected() {
    try {
      super.onListenerConnected();
      AutoClickerConfig.init(this);
      ShizukuInput.attach(this);
      Log.i(TAG, "NLS_CONNECTED enabled=" + AutoClickerConfig.isEnabled()
          + " nuclear=" + AutoClickerConfig.isNuclearMode()
          + " min=" + AutoClickerConfig.getMinPrice());
      replayActiveRideNotifications();
    } catch (Throwable t) {
      Log.e(TAG, "NLS_CONNECT_CRASH " + t.getMessage(), t);
    }
  }

  @Override
  public void onListenerDisconnected() {
    Log.w(TAG, "NLS_DISCONNECTED — requesting rebind");
    try {
      if (Build.VERSION.SDK_INT >= 24) {
        requestRebind(new ComponentName(getApplicationContext(), RideAlertListener.class));
      }
    } catch (Throwable t) {
      Log.w(TAG, "NLS_REBIND_FAIL " + t.getMessage());
    }
  }

  /** After OEM unbind/rebind, re-arm any ride heads-up still in the shade. */
  private void replayActiveRideNotifications() {
    if (!AutoClickerConfig.peekEnabled()) return;
    StatusBarNotification[] active;
    try {
      active = getActiveNotifications();
    } catch (Throwable t) {
      return;
    }
    if (active == null) return;
    int n = 0;
    for (StatusBarNotification sbn : active) {
      if (sbn == null) continue;
      String pkg = sbn.getPackageName();
      if (pkg == null || !AutoClickerConfig.isPackageMonitored(pkg)) continue;
      handleNotificationPosted(sbn);
      n++;
      if (n >= 8) break;
    }
    if (n > 0) {
      Log.i(TAG, "NLS_REPLAY posted=" + n);
    }
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    try {
      handleNotificationPosted(sbn);
    } catch (Throwable t) {
      Log.e(TAG, "NLS_CRASH " + t.getMessage(), t);
    }
  }

  private void handleNotificationPosted(StatusBarNotification sbn) {
    if (sbn == null) return;

    AutoClickerConfig.ensureInit(this);
    String pkg = sbn.getPackageName();
    if (pkg == null || !AutoClickerConfig.isPackageMonitored(pkg)) return;
    if (!AutoClickerConfig.peekEnabled()) {
      long now = SystemClock.uptimeMillis();
      if (now - lastSkipDisabledLogUptime >= SKIP_LOG_MIN_INTERVAL_MS) {
        lastSkipDisabledLogUptime = now;
        Log.w(TAG, "SKIP_DISABLED pkg=" + pkg
            + " — Auto-accept OFF (turn ON in AG rider Home)");
      }
      return;
    }

    final long tReceive = SystemClock.uptimeMillis();
    String text = notifText(sbn);
    Notification n = sbn.getNotification();
    boolean ola = AutoClickerConfig.isOlaPackage(pkg);
    boolean captain = AutoClickerConfig.isCaptainRapidoPackage(pkg);
    boolean driverPkg = ola || captain;

    if (!driverPkg && AutoClickerService.isSpamText(text)) {
      logSkip("SKIP_SPAM", pkg, sbn, text);
      return;
    }
    if (driverPkg && AutoClickerService.isDriverStatusPing(pkg, text, sbn.isOngoing())) {
      logSkip("SKIP_STATUS", pkg, sbn, text);
      return;
    }
    boolean rideAlert = AutoClickerService.isRideAlert(text, n);
    if (!rideAlert && AutoClickerService.hasTripOfferCue(text)) {
      rideAlert = true;
    }
    if (!rideAlert && looksLikeOpaqueRidePing(sbn, n, text)) {
      rideAlert = true;
      Log.w(TAG, "NLS_OPAQUE_ARM pkg=" + pkg
          + " id=" + sbn.getId()
          + " textLen=" + (text != null ? text.length() : 0));
    }
    // Ola: keep the working heads-up arm (terse / image-only / non-ongoing).
    // Rapido: only a real trip cue — random pings were causing spray.
    if (!rideAlert && ola) {
      String h = text != null ? text.toLowerCase(Locale.US) : "";
      boolean requestCue = AutoClickerService.hasTripOfferCue(h);
      if (requestCue || !sbn.isOngoing()) {
        rideAlert = true;
        Log.w(TAG, "NLS_OLA_ARM pkg=" + pkg
            + " ongoing=" + sbn.isOngoing()
            + " text=" + truncate(text, 80));
      }
    } else if (!rideAlert && captain && AutoClickerService.hasTripOfferCue(text)) {
      rideAlert = true;
    }
    if (!rideAlert) {
      logSkip("SKIP_NOT_RIDE", pkg, sbn, text);
      return;
    }

    AutoClickerService.onRideSignal(pkg, n, text, tReceive, "NLS");
    Log.w(TAG, "NLS_POST pkg=" + pkg
        + " id=" + sbn.getId()
        + " ongoing=" + sbn.isOngoing()
        + " rideAlert=true"
        + " textLen=" + (text != null ? text.length() : 0)
        + " text=" + truncate(text, 80));
  }

  /**
   * High-importance / fullscreen / non-ongoing Captain pings that failed text match.
   * Skips classic ongoing "online" FGS (id often sticky + ongoing).
   */
  private static boolean looksLikeOpaqueRidePing(
      StatusBarNotification sbn, Notification n, String text
  ) {
    if (n == null) return false;
    // Ongoing status FGS — usually "you are online", not a new offer
    if (sbn.isOngoing()) return false;
    if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
    if (n.fullScreenIntent != null) return true;
    if (Notification.CATEGORY_CALL.equals(n.category)
        || Notification.CATEGORY_ALARM.equals(n.category)
        || Notification.CATEGORY_EVENT.equals(n.category)) {
      return true;
    }
    // Ola image-only heads-ups are often empty + HIGH. Rapido must not use this
    // or status pings become random taps.
    if (AutoClickerConfig.isOlaPackage(sbn.getPackageName())) {
      if (Notification.CATEGORY_STATUS.equals(n.category)) return true;
      int len = text != null ? text.trim().length() : 0;
      int importance = n.priority;
      if (len <= 2 && importance >= Notification.PRIORITY_HIGH) return true;
    }
    int len = text != null ? text.trim().length() : 0;
    return len <= 2 && (n.flags & Notification.FLAG_INSISTENT) != 0;
  }

  private void logSkip(String reason, String pkg, StatusBarNotification sbn, String text) {
    long now = SystemClock.uptimeMillis();
    if (now - lastSkipFilterLogUptime < SKIP_LOG_MIN_INTERVAL_MS) return;
    lastSkipFilterLogUptime = now;
    Log.w(TAG, reason + " pkg=" + pkg
        + " id=" + (sbn != null ? sbn.getId() : -1)
        + " ongoing=" + (sbn != null && sbn.isOngoing())
        + " text=" + truncate(text, 80));
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    String t = s.replace('\n', ' ').trim();
    if (t.length() <= max) return t;
    return t.substring(0, max) + "…";
  }

  private String notifText(StatusBarNotification sbn) {
    StringBuilder sb = scratchSb;
    sb.setLength(0);
    Notification n = sbn.getNotification();
    if (n == null) return "";
    if (n.tickerText != null) sb.append(n.tickerText).append(' ');
    Bundle extras = n.extras;
    if (extras != null) {
      append(sb, extras.getCharSequence(Notification.EXTRA_TITLE));
      append(sb, extras.getCharSequence(Notification.EXTRA_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
      append(sb, extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
      CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
      if (lines != null) {
        for (CharSequence line : lines) append(sb, line);
      }
    }
    if (n.actions != null) {
      for (Notification.Action a : n.actions) {
        if (a != null && a.title != null) sb.append(" action:").append(a.title).append(' ');
      }
    }
    return sb.toString().toLowerCase(Locale.US);
  }

  private static void append(StringBuilder sb, CharSequence v) {
    if (v != null) sb.append(v).append(' ');
  }
}
