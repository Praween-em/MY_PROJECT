package com.ridio.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Wakes {@code :engine} after Recents swipe / OEM process kill while Auto-accept
 * is ON. Force-stop from App info still cannot be survived (Android rule).
 */
public class WatchdogAlarm extends BroadcastReceiver {

  private static final String TAG = "WatchdogAlarm";
  public static final String ACTION = "com.ridio.app.WATCHDOG";
  private static final int REQ = 7144;
  private static final long INTERVAL_MS = 25_000L;
  private static final long FIRST_MS = 8_000L;

  public static void schedule(Context context) {
    if (context == null) return;
    Context ctx = context.getApplicationContext();
    AutoClickerConfig.ensureInit(ctx);
    if (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled()) return;
    try {
      AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
      if (am == null) return;
      am.setRepeating(
          AlarmManager.ELAPSED_REALTIME_WAKEUP,
          SystemClock.elapsedRealtime() + FIRST_MS,
          INTERVAL_MS,
          pending(ctx)
      );
      Log.i(TAG, "scheduled every " + INTERVAL_MS + "ms");
    } catch (Throwable t) {
      Log.w(TAG, "schedule: " + t.getMessage());
    }
  }

  public static void cancel(Context context) {
    if (context == null) return;
    Context ctx = context.getApplicationContext();
    try {
      AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
      if (am != null) am.cancel(pending(ctx));
    } catch (Throwable ignored) {
    }
  }

  private static PendingIntent pending(Context ctx) {
    Intent i = new Intent(ctx, WatchdogAlarm.class);
    i.setAction(ACTION);
    i.setPackage(ctx.getPackageName());
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    return PendingIntent.getBroadcast(ctx, REQ, i, flags);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    if (context == null) return;
    AutoClickerConfig.init(context);
    if (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled()) {
      cancel(context);
      return;
    }
    Log.i(TAG, "tick — keep engine");
    EngineKeepAlive.ensureStarted(context);
    RecentsGuard.ensureStarted(context);
    RideAlertListener.keepBound(context);
    AutoClickerService.pokeFromAlarm();
  }
}
