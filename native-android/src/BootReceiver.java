package com.ridio.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Boot / Xiaomi quickboot / app update — restart the sticky engine if Auto-accept was ON.
 */
public class BootReceiver extends BroadcastReceiver {

  private static final String TAG = "BootReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null) return;
    final String action = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || "android.intent.action.QUICKBOOT_POWERON".equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      AutoClickerConfig.init(context);
      Log.w(TAG, "BOOT/RESUME action=" + action
          + " enabled=" + AutoClickerConfig.isEnabled()
          + " nuclear=" + AutoClickerConfig.isNuclearMode()
          + " minPrice=" + AutoClickerConfig.getMinPrice());
      EngineKeepAlive.ensureStarted(context);
      RecentsGuard.ensureStarted(context);
    }
  }
}
