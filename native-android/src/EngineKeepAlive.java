package com.ridio.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Sticky FGS in {@code :engine}. Survives Recents swipe of the UI task.
 * Same notification id as {@link AutoClickerService} so the user sees one tile.
 */
public class EngineKeepAlive extends Service {

  private static final String TAG = "EngineKeepAlive";
  static final String ACTION_START = "com.ridio.app.KEEP_ALIVE_START";
  static final String ACTION_STOP = "com.ridio.app.KEEP_ALIVE_STOP";
  private static final int NOTIFY_ID = 7142;
  private static final String CHANNEL_ID = "superridex_engine";

  public static void ensureStarted(Context context) {
    if (context == null) return;
    Context ctx = context.getApplicationContext();
    AutoClickerConfig.ensureInit(ctx);
    if (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled()) return;
    Intent i = new Intent(ctx, EngineKeepAlive.class);
    i.setAction(ACTION_START);
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(i);
      } else {
        ctx.startService(i);
      }
    } catch (Throwable t) {
      Log.w(TAG, "ensureStarted: " + t.getMessage());
    }
  }

  public static void stop(Context context) {
    if (context == null) return;
    Context ctx = context.getApplicationContext();
    try {
      Intent i = new Intent(ctx, EngineKeepAlive.class);
      i.setAction(ACTION_STOP);
      ctx.startService(i);
    } catch (Throwable t) {
      try {
        ctx.stopService(new Intent(ctx, EngineKeepAlive.class));
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    AutoClickerConfig.init(this);
    ShizukuInput.attach(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    AutoClickerConfig.ensureInit(this);
    String action = intent != null ? intent.getAction() : ACTION_START;
    if (ACTION_STOP.equals(action)
        || (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled())) {
      dropForeground();
      stopSelf();
      return START_NOT_STICKY;
    }
    promoteForeground();
    return START_STICKY;
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    AutoClickerConfig.ensureInit(this);
    if (AutoClickerConfig.peekEnabled() || AutoClickerConfig.isEnabled()) {
      promoteForeground();
      ensureStarted(this);
    }
    super.onTaskRemoved(rootIntent);
  }

  @Override
  public void onDestroy() {
    boolean keep = AutoClickerConfig.peekEnabled() || AutoClickerConfig.isEnabled();
    if (keep) {
      // OEM tore us down — ask Android to bring the sticky service back
      try {
        ensureStarted(getApplicationContext());
      } catch (Throwable ignored) {
      }
    } else {
      dropForeground();
    }
    super.onDestroy();
  }

  private void promoteForeground() {
    try {
      ensureChannel();
      int icon = getApplicationInfo().icon;
      if (icon == 0) icon = android.R.drawable.ic_dialog_info;
      boolean on = AutoClickerConfig.peekEnabled() || AutoClickerConfig.isEnabled();
      Intent open = new Intent();
      open.setClassName(getPackageName(), "com.ridio.app.MainActivity");
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
        b = new Notification.Builder(this, CHANNEL_ID);
      } else {
        b = new Notification.Builder(this);
      }
      Notification n = b
          .setContentTitle(on ? "AG rider — Auto-accept ON" : "AG rider — Auto-accept OFF")
          .setContentText(on
              ? "Running after Recents swipe — hunting Accept"
              : "Tap to open AG rider and turn Auto-accept ON")
          .setSmallIcon(icon)
          .setContentIntent(contentPi)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .build();
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      } else {
        startForeground(NOTIFY_ID, n);
      }
    } catch (Exception e) {
      Log.w(TAG, "promoteForeground: " + e.getMessage());
    }
  }

  private void dropForeground() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE);
      } else {
        stopForeground(true);
      }
    } catch (Exception ignored) {
    }
  }

  private void ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    try {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
      NotificationChannel ch = new NotificationChannel(
          CHANNEL_ID,
          "AG rider engine",
          NotificationManager.IMPORTANCE_LOW
      );
      ch.setDescription("Keeps Accept running after Recents swipe");
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    } catch (Exception ignored) {
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
