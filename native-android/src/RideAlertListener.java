package com.rapido.tap;

import android.app.Notification;
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
    super.onListenerConnected();
    AutoClickerConfig.init(this);
    ServiceHealth.init(this);
    Log.i(TAG, "NLS_CONNECTED enabled=" + AutoClickerConfig.isEnabled()
        + " nuclear=" + AutoClickerConfig.isNuclearMode()
        + " min=" + AutoClickerConfig.getMinPrice());
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    if (sbn == null) return;

    AutoClickerConfig.ensureInit(this);
    String pkg = sbn.getPackageName();
    if (pkg == null || !AutoClickerConfig.isPackageMonitored(pkg)) return;
    if (!AutoClickerConfig.peekEnabled()) {
      long now = SystemClock.uptimeMillis();
      if (now - lastSkipDisabledLogUptime >= SKIP_LOG_MIN_INTERVAL_MS) {
        lastSkipDisabledLogUptime = now;
        Log.w(TAG, "SKIP_DISABLED pkg=" + pkg
            + " — Auto-accept OFF (turn ON in SUPER RIDEX Home)");
      }
      return;
    }

    final long tReceive = SystemClock.uptimeMillis();
    String text = notifText(sbn);
    Notification n = sbn.getNotification();

    if (AutoClickerService.isSpamText(text)) {
      logSkip("SKIP_SPAM", pkg, sbn, text);
      return;
    }
    boolean rideAlert = AutoClickerService.isRideAlert(text, n);
    // Opaque / high-importance Captain ping with no spam text — still arm
    // (some OEMs post empty or image-only ride heads-ups). Same on every phone.
    if (!rideAlert && looksLikeOpaqueRidePing(sbn, n, text)) {
      rideAlert = true;
      Log.w(TAG, "NLS_OPAQUE_ARM pkg=" + pkg
          + " id=" + sbn.getId()
          + " textLen=" + (text != null ? text.length() : 0));
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
    if (n.category != null) {
      String c = n.category;
      if (Notification.CATEGORY_CALL.equals(c)
          || Notification.CATEGORY_ALARM.equals(c)
          || Notification.CATEGORY_EVENT.equals(c)
          || Notification.CATEGORY_STATUS.equals(c)) {
        return true;
      }
    }
    // Empty / very short opaque text at HIGH+ importance
    int len = text != null ? text.trim().length() : 0;
    int importance = Notification.PRIORITY_DEFAULT;
    try {
      if (android.os.Build.VERSION.SDK_INT >= 26) {
        // channel importance not always available here — use priority
      }
      importance = n.priority;
    } catch (Exception ignored) {
    }
    if (len <= 2 && importance >= Notification.PRIORITY_HIGH) return true;
    if (len <= 2 && sbn.getNotification() != null
        && (n.flags & Notification.FLAG_INSISTENT) != 0) {
      return true;
    }
    return false;
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
