package com.ridio.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Lives in the default (UI) process so Recents swipe delivers {@link #onTaskRemoved}.
 * Immediately restarts {@link EngineKeepAlive} in {@code :engine}.
 */
public class RecentsGuard extends Service {

  private static final String TAG = "RecentsGuard";
  static final String ACTION_START = "com.ridio.app.RECENTS_GUARD_START";
  static final String ACTION_STOP = "com.ridio.app.RECENTS_GUARD_STOP";

  public static void ensureStarted(Context context) {
    if (context == null) return;
    Context ctx = context.getApplicationContext();
    AutoClickerConfig.ensureInit(ctx);
    if (!AutoClickerConfig.isRequestedEnabled()
        && !AutoClickerConfig.peekEnabled()
        && !AutoClickerConfig.isEnabled()) return;
    Intent i = new Intent(ctx, RecentsGuard.class);
    i.setAction(ACTION_START);
    try {
      ctx.startService(i);
    } catch (Throwable t) {
      Log.w(TAG, "ensureStarted: " + t.getMessage());
    }
  }

  public static void stop(Context context) {
    if (context == null) return;
    Context ctx = context.getApplicationContext();
    try {
      Intent i = new Intent(ctx, RecentsGuard.class);
      i.setAction(ACTION_STOP);
      ctx.startService(i);
    } catch (Throwable t) {
      try {
        ctx.stopService(new Intent(ctx, RecentsGuard.class));
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    AutoClickerConfig.ensureInit(this);
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!AutoClickerConfig.isRequestedEnabled()
        && !AutoClickerConfig.peekEnabled()
        && !AutoClickerConfig.isEnabled()) {
      stopSelf();
      return START_NOT_STICKY;
    }
    EngineKeepAlive.ensureStarted(this);
    return START_STICKY;
  }

  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    Log.w(TAG, "onTaskRemoved — UI swiped; keeping :engine only");
    AutoClickerConfig.ensureInit(this);
    final Context app = getApplicationContext();
    final boolean keep = AutoClickerConfig.isRequestedEnabled()
        || AutoClickerConfig.peekEnabled()
        || AutoClickerConfig.isEnabled();
    if (keep) {
      // Do not restart RecentsGuard in the dying UI task — that loop caused ANRs.
      MAIN.post(() -> {
        try {
          EngineKeepAlive.ensureStarted(app);
          WatchdogAlarm.schedule(app);
        } catch (Throwable t) {
          Log.w(TAG, "rearm after swipe: " + t.getMessage());
        }
      });
    }
    super.onTaskRemoved(rootIntent);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
