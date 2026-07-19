package com.playnix.app;

import android.app.Notification;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Locale;

/**
 * Earliest ride ping. Ongoing status-bar alerts are included — Rapido often
 * posts ride offers as ongoing notifications / heads-up overlays.
 */
public class RideAlertListener extends NotificationListenerService {

  private static final String TAG = "RideAlertListener";
  private static final boolean DEBUG_LOG = false;

  /** Reused on NLS binder thread — not concurrent with itself for a single listener. */
  private final StringBuilder scratchSb = new StringBuilder(256);

  @Override
  public void onListenerConnected() {
    super.onListenerConnected();
    Log.i(TAG, "NLS_CONNECTED enabled=" + AutoClickerConfig.isEnabled()
        + " nuclear=" + AutoClickerConfig.isNuclearMode());
  }

  @Override
  public void onNotificationPosted(StatusBarNotification sbn) {
    if (sbn == null) return;

    String pkg = sbn.getPackageName();
    if (pkg == null || !AutoClickerConfig.isPackageMonitored(pkg)) return;
    if (!AutoClickerConfig.isEnabled()) return;

    // Do NOT skip ongoing — captain ride alerts are often ongoing/heads-up.
    final long tReceive = SystemClock.uptimeMillis();
    String text = notifText(sbn);
    if (AutoClickerService.isSpamText(text)) {
      if (DEBUG_LOG) Log.i(TAG, "SKIP spam pkg=" + pkg);
      return;
    }

    // Prefer ride-like text; still forward empty/unknown in Nuclear (overlay may appear)
    boolean rideLike = looksLikeRide(text);
    if (!rideLike && !AutoClickerConfig.isNuclearMode()) {
      if (DEBUG_LOG) Log.i(TAG, "SKIP not-ride-like pkg=" + pkg);
      return;
    }

    if (DEBUG_LOG) {
      Log.i(TAG, "NLS_RIDE_PING pkg=" + pkg
          + " ongoing=" + sbn.isOngoing()
          + " rideLike=" + rideLike);
    }
    AutoClickerService.onRideSignal(pkg, sbn.getNotification(), text, tReceive, "NLS");
  }

  private static boolean looksLikeRide(String text) {
    if (text == null || text.isEmpty()) return true; // unknown — let a11y decide
    // text already lowercased in notifText
    if (text.contains("accept") || text.contains("स्वीकार")) return true;
    if (text.contains("₹") || text.contains("rs") || text.contains("inr") || text.contains("cash")) {
      return true;
    }
    if (text.contains("km") || text.contains("pickup") || text.contains("drop")) return true;
    if (text.contains("new order") || text.contains("new ride") || text.contains("ride request")) {
      return true;
    }
    if (text.contains("booking") || text.contains("trip") || text.contains("fare")) return true;
    return false;
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
