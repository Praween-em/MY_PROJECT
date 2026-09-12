package com.ridio.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
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
    if (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled()) return;
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
    if (!AutoClickerConfig.peekEnabled() && !AutoClickerConfig.isEnabled()) {
      stopSelf();
      return START_NOT_STICKY;
    }
    EngineKeepAlive.ensureStarted(this);
    return START_STICKY;
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    Log.w(TAG, "onTaskRemoved — UI swiped; keeping engine");
    AutoClickerConfig.ensureInit(this);
    if (AutoClickerConfig.peekEnabled() || AutoClickerConfig.isEnabled()) {
      EngineKeepAlive.ensureStarted(getApplicationContext());
      try {
        Intent restart = new Intent(getApplicationContext(), RecentsGuard.class);
        restart.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          // Guard itself is not FGS; KeepAlive in :engine is.
          getApplicationContext().startService(restart);
        } else {
          startService(restart);
        }
      } catch (Throwable t) {
        Log.w(TAG, "rearm after swipe: " + t.getMessage());
      }
    }
    super.onTaskRemoved(rootIntent);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
