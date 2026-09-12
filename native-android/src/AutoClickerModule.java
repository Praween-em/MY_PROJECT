package com.ridio.app;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;

import rikka.shizuku.Shizuku;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AutoClickerModule extends ReactContextBaseJavaModule {

  private static final String MODULE_NAME = "AutoClickerModule";
  /** Cross-process accept events from `:engine` → UI / JS. */
  public static final String ACTION_RIDE_ACCEPTED = "com.ridio.app.ACTION_RIDE_ACCEPTED";
  /** UI → `:engine` Auto-accept / nuclear toggle (sInstance is null in UI process). */
  public static final String ACTION_CONFIG_CHANGED = "com.ridio.app.ACTION_CONFIG_CHANGED";
  /** Not in all SDK stubs — use string action (API 33+) */
  private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
      "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
  private static final int SHIZUKU_PERMISSION_CODE = 7143;
  private static ReactApplicationContext reactContext;
  private BroadcastReceiver rideAcceptedBridge;
  private Promise pendingShizukuPromise;
  private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
      (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_PERMISSION_CODE) return;
        Promise p = pendingShizukuPromise;
        pendingShizukuPromise = null;
        if (p != null) {
          p.resolve(grantResult == PackageManager.PERMISSION_GRANTED);
        }
      };

  public AutoClickerModule(ReactApplicationContext context) {
    super(context);
    reactContext = context;
    AutoClickerConfig.init(context);
    registerRideAcceptedBridge(context);
    try {
      Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
      ShizukuInput.attach(context);
    } catch (Throwable t) {
      android.util.Log.w(MODULE_NAME, "Shizuku init: " + t.getMessage());
    }
  }

  /** UI process: forward accepts from `:engine` into JS. */
  private void registerRideAcceptedBridge(ReactApplicationContext context) {
    if (rideAcceptedBridge != null) return;
    rideAcceptedBridge = new BroadcastReceiver() {
      @Override
      public void onReceive(Context ctx, Intent intent) {
        if (intent == null || reactContext == null || !reactContext.hasActiveReactInstance()) {
          return;
        }
        emitToJs(
            intent.getStringExtra("packageName"),
            intent.getIntExtra("price", 0),
            intent.getStringExtra("label"),
            intent.getIntExtra("latencyMs", -1),
            intent.getStringExtra("mode")
        );
      }
    };
    IntentFilter filter = new IntentFilter(ACTION_RIDE_ACCEPTED);
    try {
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(rideAcceptedBridge, filter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        context.registerReceiver(rideAcceptedBridge, filter);
      }
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "ride bridge register failed: " + e.getMessage());
    }
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  // ─── Permission helpers ───────────────────────────────────────────────────

  @ReactMethod
  public void isAccessibilityEnabled(Promise promise) {
    try {
      promise.resolve(isOurAccessibilityServiceEnabled(getReactApplicationContext()));
    } catch (Throwable t) {
      promise.resolve(false);
    }
  }

  @ReactMethod
  public void openAccessibilitySettings() {
    Context ctx = getReactApplicationContext();
    // Android 13+: opens directly on this app's accessibility page (triggers restricted dialog)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      try {
        Intent details = new Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        details.putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            new ComponentName(ctx.getPackageName(), AutoClickerService.class.getName())
        );
        details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(details);
        return;
      } catch (Exception ignored) {
        // fall through to generic accessibility settings
      }
    }
    try {
      Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "openAccessibilitySettings: " + e.getMessage());
    }
  }

  /**
   * Opens this app's system settings page where Android 13+ users can tap
   * the ⋮ menu → "Allow restricted settings" (required for sideloaded APKs).
   */
  @ReactMethod
  public void openAppInfoSettings() {
    try {
      Context ctx = getReactApplicationContext();
      Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
      intent.setData(Uri.parse("package:" + ctx.getPackageName()));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "openAppInfoSettings: " + e.getMessage());
    }
  }

  @ReactMethod
  public void isNotificationListenerEnabled(Promise promise) {
    try {
      promise.resolve(isOurNotificationListenerEnabled(getReactApplicationContext()));
    } catch (Throwable t) {
      promise.resolve(false);
    }
  }

  @ReactMethod
  public void openNotificationListenerSettings() {
    try {
      Context ctx = getReactApplicationContext();
      Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "openNotificationListenerSettings: " + e.getMessage());
    }
  }

  @ReactMethod
  public void isOverlayPermissionGranted(Promise promise) {
    try {
      promise.resolve(Settings.canDrawOverlays(getReactApplicationContext()));
    } catch (Throwable t) {
      promise.resolve(false);
    }
  }

  @ReactMethod
  public void openOverlaySettings() {
    try {
      Context ctx = getReactApplicationContext();
      Intent intent = new Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:" + ctx.getPackageName())
      );
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "openOverlaySettings: " + e.getMessage());
    }
  }

  @ReactMethod
  public void isBatteryOptimizationIgnored(Promise promise) {
    try {
      PowerManager pm = (PowerManager) getReactApplicationContext().getSystemService(Context.POWER_SERVICE);
      if (pm == null) {
        promise.resolve(false);
        return;
      }
      promise.resolve(pm.isIgnoringBatteryOptimizations(getReactApplicationContext().getPackageName()));
    } catch (Throwable t) {
      promise.resolve(false);
    }
  }

  @ReactMethod
  public void openBatteryOptimizationSettings() {
    try {
      Context ctx = getReactApplicationContext();
      Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
      intent.setData(Uri.parse("package:" + ctx.getPackageName()));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "openBatteryOptimizationSettings: " + e.getMessage());
    }
  }

  @ReactMethod
  public void getShizukuStatus(Promise promise) {
    try {
      Context ctx = getReactApplicationContext();
      WritableMap map = Arguments.createMap();
      boolean installed = ShizukuInput.isInstalled(ctx);
      boolean running = ShizukuInput.isRunning();
      boolean permission = ShizukuInput.hasPermission();
      map.putBoolean("installed", installed);
      map.putBoolean("running", running);
      map.putBoolean("permission", permission);
      map.putBoolean("ready", ShizukuInput.isReady());
      map.putString("state", ShizukuInput.state(ctx));
      promise.resolve(map);
    } catch (Throwable t) {
      WritableMap map = Arguments.createMap();
      map.putBoolean("installed", false);
      map.putBoolean("running", false);
      map.putBoolean("permission", false);
      map.putBoolean("ready", false);
      map.putString("state", "missing");
      promise.resolve(map);
    }
  }

  @ReactMethod
  public void requestShizukuPermission(Promise promise) {
    try {
      ShizukuInput.attach(getReactApplicationContext());
      if (!ShizukuInput.isRunning()) {
        promise.resolve(false);
        return;
      }
      if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        promise.resolve(true);
        return;
      }
      pendingShizukuPromise = promise;
      Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
    } catch (Throwable t) {
      promise.resolve(false);
    }
  }

  @ReactMethod
  public void openShizukuApp() {
    Context ctx = getReactApplicationContext();
    try {
      Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ShizukuInput.SHIZUKU_PACKAGE);
      if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(launch);
        return;
      }
    } catch (Exception ignored) {
    }
    openShizukuPlayStore();
  }

  @ReactMethod
  public void openShizukuPlayStore() {
    Context ctx = getReactApplicationContext();
    try {
      Intent market = new Intent(
          Intent.ACTION_VIEW,
          Uri.parse("market://details?id=" + ShizukuInput.SHIZUKU_PACKAGE)
      );
      market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(market);
    } catch (Exception e) {
      try {
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuInput.PLAY_STORE_URL));
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(web);
      } catch (Exception ignored) {
      }
    }
  }

  // ─── Service config ───────────────────────────────────────────────────────

  @ReactMethod
  public void setServiceEnabled(boolean enabled, Promise promise) {
    try {
      boolean prev = AutoClickerConfig.peekEnabled();
      AutoClickerConfig.setEnabled(enabled);
      broadcastConfigChanged(getReactApplicationContext());
      AutoClickerService.onConfigChanged();
      Context app = getReactApplicationContext();
      if (enabled) {
        EngineKeepAlive.ensureStarted(app);
        RecentsGuard.ensureStarted(app);
      } else {
        EngineKeepAlive.stop(app);
        RecentsGuard.stop(app);
      }
      if (prev != enabled) {
        android.util.Log.i("AutoClickerModule", "setServiceEnabled " + prev + "→" + enabled);
      }
      promise.resolve(enabled);
    } catch (Throwable t) {
      promise.resolve(enabled);
    }
  }

  /** Push master/nuclear into `:engine` — required because a11y sInstance lives there. */
  static void broadcastConfigChanged(Context ctx) {
    if (ctx == null) return;
    try {
      Intent i = new Intent(ACTION_CONFIG_CHANGED);
      i.setPackage(ctx.getPackageName());
      i.putExtra("enabled", AutoClickerConfig.peekEnabled());
      i.putExtra("nuclear_mode", AutoClickerConfig.peekNuclearMode());
      i.putExtra("min_price", AutoClickerConfig.getMinPrice());
      i.putExtra("max_pickup", AutoClickerConfig.getMaxPickup());
      ctx.sendBroadcast(i);
      android.util.Log.i(MODULE_NAME, "CONFIG_BROADCAST enabled="
          + AutoClickerConfig.peekEnabled()
          + " nuclear=" + AutoClickerConfig.peekNuclearMode()
          + " min=" + AutoClickerConfig.getMinPrice()
          + " maxPickup=" + AutoClickerConfig.getMaxPickup());
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "CONFIG_BROADCAST fail: " + e.getMessage());
    }
  }

  @ReactMethod
  public void isServiceEnabled(Promise promise) {
    promise.resolve(AutoClickerConfig.isEnabled());
  }

  @ReactMethod
  public void setMinPrice(int price, Promise promise) {
    AutoClickerConfig.setMinPrice(price);
    broadcastConfigChanged(getReactApplicationContext());
    promise.resolve(price);
  }

  @ReactMethod
  public void setMaxPickup(double km, Promise promise) {
    float v = (float) Math.max(0d, km);
    AutoClickerConfig.setMaxPickup(v);
    broadcastConfigChanged(getReactApplicationContext());
    promise.resolve((double) v);
  }

  @ReactMethod
  public void setDelayMs(int delay, Promise promise) {
    AutoClickerConfig.setDelayMs(0);
    promise.resolve(0);
  }

  @ReactMethod
  public void setNuclearMode(boolean enabled, Promise promise) {
    AutoClickerConfig.setNuclearMode(true);
    AutoClickerConfig.setDelayMs(0);
    broadcastConfigChanged(getReactApplicationContext());
    AutoClickerService.onConfigChanged();
    promise.resolve(true);
  }

  /** Continuous FG spray API — HARD OFF (MeClicker hunt→click→micro-burst only). */
  @ReactMethod
  public void setContinuousForegroundTap(boolean enabled, Promise promise) {
    AutoClickerConfig.setContinuousForegroundTap(false);
    AutoClickerService.onConfigChanged();
    promise.resolve(false);
  }

  @ReactMethod
  public void setMonitoredPackages(ReadableArray packages, Promise promise) {
    try {
      Set<String> set = new HashSet<>();
      if (packages != null) {
        for (int i = 0; i < packages.size(); i++) {
          String pkg = packages.getString(i);
          if (pkg != null && !pkg.isEmpty()) {
            set.add(pkg);
          }
        }
      }
      AutoClickerConfig.setMonitoredPackages(set);
      promise.resolve(true);
    } catch (Throwable t) {
      promise.resolve(false);
    }
  }

  @ReactMethod
  public void getServiceStatus(Promise promise) {
    try {
      WritableMap map = Arguments.createMap();
      map.putBoolean("enabled", AutoClickerConfig.isEnabled());
      map.putBoolean("nuclearMode", AutoClickerConfig.isNuclearMode());
      map.putBoolean("continuousForegroundTap", AutoClickerConfig.isContinuousForegroundTap());
      map.putInt("minPrice", AutoClickerConfig.getMinPrice());
      map.putDouble("maxPickup", AutoClickerConfig.getMaxPickup());
      map.putInt("delayMs", AutoClickerConfig.getDelayMs());
      broadcastConfigChanged(getReactApplicationContext());
      map.putBoolean("accessibilityEnabled", isOurAccessibilityServiceEnabled(getReactApplicationContext()));
      map.putBoolean(
          "notificationListenerEnabled",
          isOurNotificationListenerEnabled(getReactApplicationContext())
      );
      map.putBoolean("shizukuInstalled", ShizukuInput.isInstalled(getReactApplicationContext()));
      map.putBoolean("shizukuRunning", ShizukuInput.isRunning());
      map.putBoolean("shizukuPermission", ShizukuInput.hasPermission());
      map.putBoolean("shizukuReady", ShizukuInput.isReady());

      WritableArray pkgs = Arguments.createArray();
      for (String pkg : AutoClickerConfig.getMonitoredPackages()) {
        pkgs.pushString(pkg);
      }
      map.putArray("monitoredPackages", pkgs);
      promise.resolve(map);
    } catch (Throwable t) {
      WritableMap map = Arguments.createMap();
      map.putBoolean("enabled", false);
      map.putBoolean("nuclearMode", true);
      map.putBoolean("accessibilityEnabled", false);
      map.putBoolean("notificationListenerEnabled", false);
      map.putBoolean("shizukuReady", false);
      promise.resolve(map);
    }
  }

  /** Permission / OEM checklist for Service Reliability. No diagnose telemetry. */
  @ReactMethod
  public void getServiceHealth(Promise promise) {
    try {
      Context ctx = getReactApplicationContext();
      boolean a11y = isOurAccessibilityServiceEnabled(ctx);
      boolean master = AutoClickerConfig.isEnabled();
      boolean batteryOk = false;
      try {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
          batteryOk = pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        }
      } catch (Exception ignored) {
      }
      boolean nlsOk = isOurNotificationListenerEnabled(ctx);
      boolean shizukuReady = ShizukuInput.isReady();
      WritableMap map = Arguments.createMap();
      map.putBoolean("accessibilityEnabled", a11y);
      map.putBoolean("serviceConnected", a11y);
      map.putBoolean("masterEnabled", master);
      map.putBoolean("batteryOptimizationOk", batteryOk);
      map.putBoolean("notificationListenerEnabled", nlsOk);
      map.putBoolean("shizukuInstalled", ShizukuInput.isInstalled(ctx));
      map.putBoolean("shizukuRunning", ShizukuInput.isRunning());
      map.putBoolean("shizukuPermission", ShizukuInput.hasPermission());
      map.putBoolean("shizukuReady", shizukuReady);
      map.putString("shizukuState", ShizukuInput.state(ctx));
      map.putString("oemId", ServiceHealth.detectOemId());
      map.putString("oemLabel", ServiceHealth.detectOemLabel());
      map.putString("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
      map.putString("model", Build.MODEL != null ? Build.MODEL : "");
      map.putString("autostartStatus", "manual");
      map.putString("backgroundStatus", "manual");
      promise.resolve(map);
    } catch (Throwable t) {
      WritableMap map = Arguments.createMap();
      map.putBoolean("accessibilityEnabled", false);
      map.putBoolean("serviceConnected", false);
      map.putBoolean("masterEnabled", false);
      map.putBoolean("batteryOptimizationOk", false);
      map.putBoolean("notificationListenerEnabled", false);
      map.putBoolean("shizukuReady", false);
      map.putString("oemId", "generic");
      map.putString("oemLabel", "Android");
      promise.resolve(map);
    }
  }

  // Required for NativeEventEmitter
  @ReactMethod
  public void addListener(String eventName) {}

  @ReactMethod
  public void removeListeners(double count) {}

  // ─── Event emission ───────────────────────────────────────────────────────

  static void emitRideAccepted(String packageName, int price, String label) {
    emitRideAccepted(packageName, price, label, -1, "Standard");
  }

  static void emitRideAccepted(
      String packageName,
      int price,
      String label,
      int latencyMs,
      String mode
  ) {
    // Always broadcast — Accept engine may run in `:engine` after swipe-kill of UI
    Context ctx = reactContext != null ? reactContext : AutoClickerConfig.getAppContext();
    if (ctx != null) {
      try {
        Intent i = new Intent(ACTION_RIDE_ACCEPTED);
        i.setPackage(ctx.getPackageName());
        i.putExtra("packageName", packageName != null ? packageName : "");
        i.putExtra("price", price);
        i.putExtra("label", label != null ? label : "");
        i.putExtra("latencyMs", latencyMs);
        i.putExtra("mode", mode != null ? mode : "Standard");
        ctx.sendBroadcast(i);
      } catch (Exception e) {
        android.util.Log.w("AutoClickerModule", "broadcast accept failed: " + e.getMessage());
      }
    }
    // Same-process fast path (UI still alive in this process)
    if (reactContext != null && reactContext.hasActiveReactInstance()) {
      emitToJs(packageName, price, label, latencyMs, mode);
    }
  }

  private static void emitToJs(
      String packageName,
      int price,
      String label,
      int latencyMs,
      String mode
  ) {
    if (reactContext == null || !reactContext.hasActiveReactInstance()) return;
    WritableMap params = Arguments.createMap();
    params.putString("packageName", packageName != null ? packageName : "");
    params.putInt("price", price);
    params.putString("label", label != null ? label : "");
    params.putInt("latencyMs", latencyMs);
    params.putString("mode", mode != null ? mode : "Standard");
    params.putDouble("timestamp", System.currentTimeMillis());
    reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit("onRideAccepted", params);
  }

  private static boolean isOurNotificationListenerEnabled(Context context) {
    String flat = Settings.Secure.getString(
        context.getContentResolver(),
        "enabled_notification_listeners"
    );
    if (TextUtils.isEmpty(flat)) return false;
    String needle = new ComponentName(
        context.getPackageName(),
        RideAlertListener.class.getName()
    ).flattenToString();
    for (String entry : flat.split(":")) {
      if (needle.equalsIgnoreCase(entry)) return true;
      ComponentName cn = ComponentName.unflattenFromString(entry);
      if (cn != null
          && context.getPackageName().equals(cn.getPackageName())
          && RideAlertListener.class.getName().equals(cn.getClassName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isOurAccessibilityServiceEnabled(Context context) {
    AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    if (am == null) return false;

    List<AccessibilityServiceInfo> services =
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

    String ourPackage = context.getPackageName();
    for (AccessibilityServiceInfo info : services) {
      if (info.getResolveInfo() != null
          && info.getResolveInfo().serviceInfo != null
          && ourPackage.equals(info.getResolveInfo().serviceInfo.packageName)
          && AutoClickerService.class.getName().equals(info.getResolveInfo().serviceInfo.name)) {
        return true;
      }
    }

    // Fallback: check Settings.Secure string
    String enabled = Settings.Secure.getString(
        context.getContentResolver(),
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    );
    return enabled != null && enabled.contains(ourPackage);
  }
}
