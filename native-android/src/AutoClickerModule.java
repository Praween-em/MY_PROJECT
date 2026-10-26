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
import com.facebook.react.bridge.ReadableMap;
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
  /** Native runtime updates sent from the isolated engine process to the UI process. */
  public static final String ACTION_RUNTIME_STATUS = "com.ridio.app.ACTION_RUNTIME_STATUS";
  /** Not in all SDK stubs — use string action (API 33+) */
  private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
      "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
  private static final int SHIZUKU_PERMISSION_CODE = 7143;
  private static ReactApplicationContext reactContext;
  private BroadcastReceiver rideAcceptedBridge;
  private BroadcastReceiver runtimeStatusBridge;
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
    registerRuntimeStatusBridge(context);
    try {
      Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
      ShizukuInput.attach(context);
    } catch (Throwable t) {
      android.util.Log.w(MODULE_NAME, "Shizuku init: " + t.getMessage());
    }
  }

  private void registerRuntimeStatusBridge(ReactApplicationContext context) {
    if (runtimeStatusBridge != null) return;
    runtimeStatusBridge = new BroadcastReceiver() {
      @Override
      public void onReceive(Context ctx, Intent intent) {
        if (intent == null || reactContext == null || !reactContext.hasActiveReactInstance()) return;
        emitRuntimeStatusToJs(buildRuntimeStatus(ctx, intent.getStringExtra("reason")));
      }
    };
    try {
      IntentFilter filter = new IntentFilter(ACTION_RUNTIME_STATUS);
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(runtimeStatusBridge, filter, Context.RECEIVER_NOT_EXPORTED);
      } else {
        context.registerReceiver(runtimeStatusBridge, filter);
      }
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "runtime bridge register failed: " + e.getMessage());
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
            intent.getStringExtra("mode"),
            intent.getIntExtra("acceptedCount", -1)
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
      boolean prev = AutoClickerConfig.isRequestedEnabled();
      AutoClickerConfig.setEnabled(enabled);
      broadcastConfigChanged(getReactApplicationContext());
      AutoClickerService.onConfigChanged();
      Context app = getReactApplicationContext();
      if (enabled) {
        EngineKeepAlive.ensureStarted(app);
        RecentsGuard.ensureStarted(app);
        WatchdogAlarm.schedule(app);
      } else {
        WatchdogAlarm.cancel(app);
        EngineKeepAlive.stop(app);
        RecentsGuard.stop(app);
      }
      if (prev != enabled) {
        android.util.Log.i("AutoClickerModule", "setServiceEnabled " + prev + "→" + enabled);
      }
      emitRuntimeStatus(getReactApplicationContext(), enabled ? "legacy-enable" : "legacy-disable");
      promise.resolve(buildRuntimeStatus(getReactApplicationContext(), null));
    } catch (Throwable t) {
      WritableMap status = buildRuntimeStatus(getReactApplicationContext(), "legacy-config-error");
      status.putString("state", "ERROR");
      status.putString("blockingReason", "CONFIG_ERROR");
      status.putString("lastError", t.getMessage() != null ? t.getMessage() : "Unable to change service state");
      promise.resolve(status);
    }
  }

  /**
   * The authoritative bridge contract. React passes a complete desired config;
   * native persists and applies it as one revision before waking the engine.
   */
  @ReactMethod
  public void applyAutomationConfig(ReadableMap config, Promise promise) {
    Context ctx = getReactApplicationContext();
    try {
      if (config == null) throw new IllegalArgumentException("Configuration is required");
      boolean nextEnabled = config.hasKey("enabled") && !config.isNull("enabled")
          && config.getBoolean("enabled");
      int fare = config.hasKey("minPrice") && !config.isNull("minPrice")
          ? Math.max(0, (int) config.getDouble("minPrice")) : 0;
      int maxFare = config.hasKey("maxPrice") && !config.isNull("maxPrice")
          ? Math.max(0, (int) config.getDouble("maxPrice")) : 0;
      float minPickup = config.hasKey("minPickup") && !config.isNull("minPickup")
          ? Math.max(0f, (float) config.getDouble("minPickup")) : 0f;
      float pickup = config.hasKey("maxPickup") && !config.isNull("maxPickup")
          ? Math.max(0f, (float) config.getDouble("maxPickup")) : 0f;
      float maxDrop = config.hasKey("maxDrop") && !config.isNull("maxDrop")
          ? Math.max(0f, (float) config.getDouble("maxDrop")) : 0f;
      Set<String> packages = new HashSet<>();
      if (config.hasKey("monitoredPackages") && !config.isNull("monitoredPackages")) {
        ReadableArray values = config.getArray("monitoredPackages");
        if (values != null) {
          for (int i = 0; i < values.size(); i++) {
            String pkg = values.getString(i);
            if (pkg != null && !pkg.trim().isEmpty()) packages.add(pkg.trim());
          }
        }
      }
      AutoClickerConfig.init(ctx);
      applyEntitlementFromConfig(config);
      long revision = AutoClickerConfig.applyAutomationConfig(
          nextEnabled, fare, maxFare, minPickup, pickup, maxDrop, packages);
      broadcastConfigChanged(ctx);
      AutoClickerService.onConfigChanged();
      if (nextEnabled) {
        EngineKeepAlive.ensureStarted(ctx);
        RecentsGuard.ensureStarted(ctx);
        WatchdogAlarm.schedule(ctx);
      } else {
        WatchdogAlarm.cancel(ctx);
        EngineKeepAlive.stop(ctx);
        RecentsGuard.stop(ctx);
      }
      emitRuntimeStatus(ctx, "config-applied:" + revision);
      promise.resolve(buildRuntimeStatus(ctx, null));
    } catch (Throwable t) {
      WritableMap result = buildRuntimeStatus(ctx, "config-error");
      result.putString("state", "ERROR");
      result.putString("blockingReason", "CONFIG_ERROR");
      result.putString("lastError", t.getMessage() != null ? t.getMessage() : "Configuration failed");
      promise.resolve(result);
    }
  }

  @ReactMethod
  public void getAutomationStatus(Promise promise) {
    promise.resolve(buildRuntimeStatus(getReactApplicationContext(), null));
  }

  /** Refresh the native entitlement cache after login/payment/server refresh. */
  @ReactMethod
  public void updateEntitlement(ReadableMap entitlement, Promise promise) {
    try {
      if (entitlement == null) throw new IllegalArgumentException("Entitlement is required");
      boolean active = entitlement.hasKey("active") && !entitlement.isNull("active")
          && entitlement.getBoolean("active");
      boolean deviceAllowed = !entitlement.hasKey("deviceAllowed") || entitlement.isNull("deviceAllowed")
          || entitlement.getBoolean("deviceAllowed");
      long endMs = entitlement.hasKey("subscriptionEndMs") && !entitlement.isNull("subscriptionEndMs")
          ? Math.max(0L, (long) entitlement.getDouble("subscriptionEndMs")) : 0L;
      AutoClickerConfig.applyEntitlementSnapshot(active, deviceAllowed, endMs);
      broadcastConfigChanged(getReactApplicationContext());
      AutoClickerService.onConfigChanged();
      emitRuntimeStatus(getReactApplicationContext(), "entitlement-updated");
      promise.resolve(buildRuntimeStatus(getReactApplicationContext(), null));
    } catch (Throwable t) {
      WritableMap status = buildRuntimeStatus(getReactApplicationContext(), "entitlement-error");
      status.putString("lastError", t.getMessage() != null ? t.getMessage() : "Entitlement update failed");
      promise.resolve(status);
    }
  }

  private static void applyEntitlementFromConfig(ReadableMap config) {
    if (!config.hasKey("entitlement") || config.isNull("entitlement")) return;
    ReadableMap entitlement = config.getMap("entitlement");
    if (entitlement == null) return;
    boolean active = entitlement.hasKey("active") && !entitlement.isNull("active")
        && entitlement.getBoolean("active");
    boolean deviceAllowed = !entitlement.hasKey("deviceAllowed") || entitlement.isNull("deviceAllowed")
        || entitlement.getBoolean("deviceAllowed");
    long endMs = entitlement.hasKey("subscriptionEndMs") && !entitlement.isNull("subscriptionEndMs")
        ? Math.max(0L, (long) entitlement.getDouble("subscriptionEndMs")) : 0L;
    AutoClickerConfig.applyEntitlementSnapshot(active, deviceAllowed, endMs);
  }

  /** Push a complete snapshot into `:engine` — sInstance is null in the UI process. */
  static void broadcastConfigChanged(Context ctx) {
    if (ctx == null) return;
    try {
      Intent i = new Intent(ACTION_CONFIG_CHANGED);
      i.setPackage(ctx.getPackageName());
      boolean requested = AutoClickerConfig.isRequestedEnabled();
      boolean entitled = AutoClickerConfig.isEntitled();
      long revision = AutoClickerConfig.getConfigRevision();
      i.putExtra(ConfigSnapshot.EXTRA_REQUESTED_ENABLED, requested);
      i.putExtra(ConfigSnapshot.EXTRA_ENTITLEMENT_KNOWN, AutoClickerConfig.isEntitlementKnown());
      i.putExtra(ConfigSnapshot.EXTRA_ENTITLEMENT_ACTIVE, AutoClickerConfig.isEntitlementActive());
      i.putExtra(ConfigSnapshot.EXTRA_ENTITLEMENT_DEVICE_ALLOWED, AutoClickerConfig.isDeviceAllowed());
      i.putExtra(ConfigSnapshot.EXTRA_ENTITLEMENT_END_MS, AutoClickerConfig.getEntitlementEndMs());
      i.putExtra(ConfigSnapshot.EXTRA_CONFIG_REVISION, revision);
      // Legacy extra now carries requested intent, never effective enablement.
      i.putExtra("enabled", requested);
      i.putExtra("nuclear_mode", AutoClickerConfig.peekNuclearMode());
      i.putExtra("min_price", AutoClickerConfig.getMinPrice());
      i.putExtra("max_fare", AutoClickerConfig.getMaxFare());
      i.putExtra("min_pickup", AutoClickerConfig.getMinPickup());
      i.putExtra("max_pickup", AutoClickerConfig.getMaxPickup());
      i.putExtra("max_drop", AutoClickerConfig.getMaxDrop());
      ctx.sendBroadcast(i);
      android.util.Log.i(MODULE_NAME, "CONFIG_BROADCAST rev=" + revision
          + " requested=" + requested
          + " entitled=" + entitled
          + " effective=" + (requested && entitled)
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
      map.putInt("maxPrice", AutoClickerConfig.getMaxFare());
      map.putDouble("minPickup", AutoClickerConfig.getMinPickup());
      map.putDouble("maxPickup", AutoClickerConfig.getMaxPickup());
      map.putDouble("maxDrop", AutoClickerConfig.getMaxDrop());
      map.putInt("delayMs", AutoClickerConfig.getDelayMs());
      map.putInt("acceptedCount", AutoClickerConfig.getAcceptedCount());
      map.putInt("lastLatencyMs", AutoClickerConfig.getLastLatencyMs());
      broadcastConfigChanged(getReactApplicationContext());
      boolean a11yOn = isOurAccessibilityServiceEnabled(getReactApplicationContext());
      boolean engineLive = AutoClickerConfig.isEngineHeartbeatFresh();
      map.putBoolean("accessibilityEnabled", a11yOn);
      map.putBoolean("engineHeartbeatFresh", engineLive);
      map.putBoolean("serviceConnected", a11yOn && engineLive);
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
      boolean engineLive = AutoClickerConfig.isEngineHeartbeatFresh();
      map.putBoolean("accessibilityEnabled", a11y);
      map.putBoolean("engineHeartbeatFresh", engineLive);
      map.putBoolean("serviceConnected", a11y && engineLive);
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

  /** Broadcast from either process. The UI process turns it into a JS event. */
  public static void emitRuntimeStatus(Context context, String reason) {
    if (context == null) return;
    try {
      Intent i = new Intent(ACTION_RUNTIME_STATUS);
      i.setPackage(context.getPackageName());
      i.putExtra("reason", reason != null ? reason : "status-changed");
      context.sendBroadcast(i);
    } catch (Exception e) {
      android.util.Log.w(MODULE_NAME, "runtime status broadcast failed: " + e.getMessage());
    }
  }

  /**
   * Status for React. Computed in this process from the local snapshot.
   * serviceConnected requires Settings enablement AND a fresh :engine heartbeat.
   */
  private static WritableMap buildRuntimeStatus(Context context, String reason) {
    WritableMap map = Arguments.createMap();
    AutoClickerConfig.ensureInit(context);
    boolean requested = AutoClickerConfig.isRequestedEnabled();
    boolean accessibility = isOurAccessibilityServiceEnabled(context);
    boolean engineLive = AutoClickerConfig.isEngineHeartbeatFresh();
    boolean serviceConnected = accessibility && engineLive;
    boolean notifications = isOurNotificationListenerEnabled(context);
    boolean shizuku = ShizukuInput.isReady();
    boolean batteryOk = false;
    try {
      PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
      batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    } catch (Exception ignored) {
    }

    String state = "STOPPED";
    String blocking = "";
    if (requested && !AutoClickerConfig.isEntitled()) {
      state = "BLOCKED";
      blocking = AutoClickerConfig.entitlementBlockReason();
    } else if (requested && !accessibility) {
      state = "BLOCKED";
      blocking = "ACCESSIBILITY_REQUIRED";
    } else if (requested) {
      // Heartbeat miss is DEGRADED, never BLOCKED. UI process prefs often
      // cannot see :engine writes, and BLOCKED was flipping Auto-accept off
      // on every screen change.
      state = (!engineLive || !notifications || !batteryOk) ? "DEGRADED" : "RUNNING";
      if (!engineLive) blocking = "ACCESSIBILITY_NOT_RUNNING";
      else if (!notifications) blocking = "NOTIFICATION_ACCESS_RECOMMENDED";
      else if (!batteryOk) blocking = "BATTERY_OPTIMIZATION_RECOMMENDED";
    }

    map.putString("state", state);
    map.putString("blockingReason", blocking);
    map.putString("reason", reason != null ? reason : "");
    map.putBoolean("requestedEnabled", requested);
    map.putBoolean("entitlementValid", AutoClickerConfig.isEntitled());
    map.putBoolean("entitlementKnown", AutoClickerConfig.isEntitlementKnown());
    map.putBoolean("deviceAllowed", AutoClickerConfig.isDeviceAllowed());
    map.putBoolean("effectiveEnabled", AutoClickerConfig.isEnabled());
    map.putBoolean("enabled", "RUNNING".equals(state) || "DEGRADED".equals(state));
    map.putBoolean("accessibilityEnabled", accessibility);
    map.putBoolean("engineHeartbeatFresh", engineLive);
    map.putBoolean("notificationListenerEnabled", notifications);
    map.putBoolean("batteryOptimizationOk", batteryOk);
    map.putBoolean("shizukuReady", shizuku);
    map.putBoolean("serviceConnected", serviceConnected);
    map.putDouble("configRevision", (double) AutoClickerConfig.getConfigRevision());
    map.putDouble("appliedRevision", (double) AutoClickerConfig.getAppliedRevision());
    map.putInt("minPrice", AutoClickerConfig.getMinPrice());
    map.putInt("maxPrice", AutoClickerConfig.getMaxFare());
    map.putDouble("minPickup", AutoClickerConfig.getMinPickup());
    map.putDouble("maxPickup", AutoClickerConfig.getMaxPickup());
    map.putDouble("maxDrop", AutoClickerConfig.getMaxDrop());
    map.putInt("acceptedCount", AutoClickerConfig.getAcceptedCount());
    map.putInt("lastLatencyMs", AutoClickerConfig.getLastLatencyMs());
    WritableArray pkgs = Arguments.createArray();
    for (String pkg : AutoClickerConfig.getMonitoredPackages()) pkgs.pushString(pkg);
    map.putArray("monitoredPackages", pkgs);
    return map;
  }

  private static void emitRuntimeStatusToJs(WritableMap status) {
    if (reactContext == null || !reactContext.hasActiveReactInstance()) return;
    reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit("onAutomationStatusChanged", status);
  }

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
        i.putExtra("acceptedCount", AutoClickerConfig.getAcceptedCount());
        ctx.sendBroadcast(i);
      } catch (Exception e) {
        android.util.Log.w("AutoClickerModule", "broadcast accept failed: " + e.getMessage());
      }
    }
    // Same-process fast path (UI still alive in this process)
    if (reactContext != null && reactContext.hasActiveReactInstance()) {
      emitToJs(packageName, price, label, latencyMs, mode, AutoClickerConfig.getAcceptedCount());
    }
  }

  private static void emitToJs(
      String packageName,
      int price,
      String label,
      int latencyMs,
      String mode,
      int acceptedCount
  ) {
    if (reactContext == null || !reactContext.hasActiveReactInstance()) return;
    WritableMap params = Arguments.createMap();
    params.putString("packageName", packageName != null ? packageName : "");
    params.putInt("price", price);
    params.putString("label", label != null ? label : "");
    params.putInt("latencyMs", latencyMs);
    params.putString("mode", mode != null ? mode : "Standard");
    params.putInt("acceptedCount", acceptedCount);
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
