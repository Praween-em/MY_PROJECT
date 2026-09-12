package com.ridio.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared config for a11y engine (MeClicker-style). UI writes; service reads.
 */
public final class AutoClickerConfig {

  private static final String PREFS = "superridex_race";

  /** Mode 0 = price filter; else = distance filter (MeClicker). */
  public static final int MODE_PRICE = 0;
  public static final int MODE_DISTANCE = 1;

  /** Default OFF — JS turns on only while subscription is active. */
  private static final AtomicBoolean enabled = new AtomicBoolean(false);
  private static final AtomicBoolean nuclearMode = new AtomicBoolean(true);
  /**
   * Continuous FG spray — HARD OFF. MeClicker hunt→click→micro-burst only.
   * Pref ignored; API always reports/stores false.
   */
  private static final AtomicBoolean continuousForegroundTap = new AtomicBoolean(false);
  private static final AtomicInteger minPrice = new AtomicInteger(0);
  private static final AtomicInteger delayMs = new AtomicInteger(0);
  private static final AtomicInteger filterMode = new AtomicInteger(MODE_PRICE);
  private static final AtomicReference<Float> maxPickup = new AtomicReference<>(0f);
  private static final AtomicReference<Float> maxDrop = new AtomicReference<>(0f);
  private static final AtomicLong lastClickAt = new AtomicLong(0);
  private static final Set<String> monitoredPackages = Collections.synchronizedSet(new HashSet<>());
  private static final Map<String, int[]> cachedTapByPackage =
      Collections.synchronizedMap(new HashMap<>());
  /** Accept coords on floating ride-alert overlays (separate from in-app CTA). */
  private static final Map<String, int[]> cachedOverlayTapByPackage =
      Collections.synchronizedMap(new HashMap<>());

  private static volatile SharedPreferences prefs;
  private static volatile Context appContext;

  public static final String PKG_OLA_DRIVER = "com.olacabs.oladriver";
  /** Play Store Rapido Captain — package id is com.rapido.rider (not .captain). */
  public static final String PKG_RAPIDO_CAPTAIN = "com.rapido.rider";
  /** Legacy / alternate id some builds used. */
  public static final String PKG_RAPIDO_CAPTAIN_LEGACY = "com.rapido.captain";

  static {
    monitoredPackages.add(PKG_OLA_DRIVER);
    monitoredPackages.add(PKG_RAPIDO_CAPTAIN);
    monitoredPackages.add(PKG_RAPIDO_CAPTAIN_LEGACY);
  }

  private AutoClickerConfig() {}

  public static void init(Context context) {
    if (context == null) return;
    appContext = context.getApplicationContext();
    prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    reloadFromPrefs();
    // Persist defaults on first run so a11y/NLS/JS share the same prefs file
    if (!prefs.contains("enabled") || !prefs.contains("nuclear_mode")
        || prefs.getBoolean("continuous_fg_tap", false)) {
      prefs.edit()
          .putBoolean("enabled", enabled.get())
          .putBoolean("nuclear_mode", nuclearMode.get())
          .putBoolean("continuous_fg_tap", false)
          .putInt("min_price", minPrice.get())
          .putInt("delay_ms", delayMs.get())
          .apply();
    }
  }

  /** Re-read disk prefs into memory (NLS/a11y can start before JS Module). */
  public static void reloadFromPrefs() {
    SharedPreferences p = prefs;
    if (p == null && appContext != null) {
      prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      p = prefs;
    }
    if (p == null) return;
    for (String pkg : monitoredPackages) {
      int x = p.getInt("tap_x_" + pkg, -1);
      int y = p.getInt("tap_y_" + pkg, -1);
      if (x > 0 && y > 0) {
        cachedTapByPackage.put(pkg, new int[] { x, y });
      }
      int ox = p.getInt("overlay_tap_x_" + pkg, -1);
      int oy = p.getInt("overlay_tap_y_" + pkg, -1);
      if (ox > 0 && oy > 0) {
        cachedOverlayTapByPackage.put(pkg, new int[] { ox, oy });
      }
    }
    filterMode.set(p.getInt("filter_mode", MODE_PRICE));
    maxPickup.set(p.getFloat("max_pickup", 0f));
    maxDrop.set(p.getFloat("max_drop", 0f));
    continuousForegroundTap.set(false); // HARD OFF — ignore stale pref
    minPrice.set(Math.max(0, p.getInt("min_price", 0)));
    nuclearMode.set(true);
    enabled.set(p.getBoolean("enabled", false));
    delayMs.set(0);
  }

  /** Ensure prefs loaded before any enable/min check (safe from NLS binder thread). */
  public static void ensureInit(Context context) {
    if (prefs != null) return;
    if (context != null) {
      init(context);
    } else if (appContext != null) {
      init(appContext);
    }
  }

  public static Context getAppContext() {
    return appContext;
  }

  public static boolean isEnabled() {
    syncCriticalFromDisk();
    return enabled.get();
  }

  public static void setEnabled(boolean value) {
    enabled.set(value);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putBoolean("enabled", value).commit(); // commit — a11y must see it now
  }

  /** In-memory only read — does not re-hit disk (safe after setEnabled / broadcast). */
  public static boolean peekEnabled() {
    return enabled.get();
  }

  public static boolean peekNuclearMode() {
    return nuclearMode.get();
  }

  /**
   * Apply toggle from UI→:engine broadcast. Also writes this process's prefs cache
   * so later {@link #syncCriticalFromDisk} does not revert to a stale false.
   */
  public static void applyEnabledFromBroadcast(boolean value) {
    enabled.set(value);
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit().putBoolean("enabled", value).commit();
    } else if (appContext != null) {
      appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit().putBoolean("enabled", value).commit();
      prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
  }

  public static void applyNuclearFromBroadcast(boolean value) {
    nuclearMode.set(true);
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit().putBoolean("nuclear_mode", true).commit();
    }
  }

  private static volatile long lastDiskSyncUptimeMs = 0L;
  /** After a config broadcast, skip disk overwrite briefly (multi-process prefs lag). */
  private static volatile long broadcastProtectUntilMs = 0L;

  /**
   * Re-read enabled/min/nuclear from disk. Fixes ColorOS process reuse where
   * in-memory AtomicBooleans stay false after prefs were updated (JS / adb).
   * Throttled — a11y hot path calls isEnabled() extremely often.
   */
  private static void syncCriticalFromDisk() {
    if (prefs == null && appContext != null) {
      init(appContext);
    }
    SharedPreferences p = prefs;
    if (p == null) return;
    long now = android.os.SystemClock.uptimeMillis();
    // Broadcast just applied truth — don't let stale process-local prefs flip it back OFF
    if (now < broadcastProtectUntilMs) return;
    if (now - lastDiskSyncUptimeMs < 400L) return;
    lastDiskSyncUptimeMs = now;
    enabled.set(p.getBoolean("enabled", false));
    nuclearMode.set(true);
    minPrice.set(Math.max(0, p.getInt("min_price", 0)));
    maxPickup.set(p.getFloat("max_pickup", 0f));
  }

  public static void applyFiltersFromBroadcast(int minFare, float pickupKm) {
    minPrice.set(Math.max(0, minFare));
    maxPickup.set(Math.max(0f, pickupKm));
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit().putInt("min_price", minPrice.get()).putFloat("max_pickup", maxPickup.get()).commit();
    }
    broadcastProtectUntilMs = android.os.SystemClock.uptimeMillis() + 5000L;
    lastDiskSyncUptimeMs = 0L;
  }

  public static void markBroadcastApplied() {
    broadcastProtectUntilMs = android.os.SystemClock.uptimeMillis() + 5000L;
    lastDiskSyncUptimeMs = 0L;
  }

  public static boolean isNuclearMode() {
    syncCriticalFromDisk();
    return nuclearMode.get();
  }

  public static void setNuclearMode(boolean value) {
    nuclearMode.set(true);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putBoolean("nuclear_mode", true).commit();
  }

  public static boolean isContinuousForegroundTap() {
    return continuousForegroundTap.get();
  }

  public static void setContinuousForegroundTap(boolean value) {
    // HARD OFF — ignore enable attempts (MeClicker path only)
    continuousForegroundTap.set(false);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putBoolean("continuous_fg_tap", false).apply();
  }

  public static int getMinPrice() {
    syncCriticalFromDisk();
    return Math.max(0, minPrice.get());
  }

  public static void setMinPrice(int value) {
    int v = Math.max(0, value);
    minPrice.set(v);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putInt("min_price", v).commit();
  }

  public static int getDelayMs() {
    return delayMs.get();
  }

  public static void setDelayMs(int value) {
    delayMs.set(0);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putInt("delay_ms", 0).apply();
  }

  public static int getFilterMode() {
    return filterMode.get();
  }

  public static void setFilterMode(int mode) {
    filterMode.set(mode);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putInt("filter_mode", mode).apply();
  }

  public static float getMaxPickup() {
    syncCriticalFromDisk();
    Float v = maxPickup.get();
    return v != null ? Math.max(0f, v) : 0f;
  }

  public static void setMaxPickup(float km) {
    float v = Math.max(0f, km);
    maxPickup.set(v);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putFloat("max_pickup", v).commit();
  }

  public static float getMaxDrop() {
    Float v = maxDrop.get();
    return v != null ? v : 0f;
  }

  public static void setMaxDrop(float km) {
    maxDrop.set(km);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putFloat("max_drop", km).apply();
  }

  public static boolean isPackageMonitored(String packageName) {
    if (packageName == null) return false;
    if (monitoredPackages.contains(packageName)) return true;
    return isTargetPackage(packageName);
  }

  /** Ola / Rapido Captain — kept name for race-engine call sites. */
  public static boolean isRapidoPackage(String packageName) {
    return isTargetPackage(packageName);
  }

  public static boolean isTargetPackage(String packageName) {
    if (packageName == null) return false;
    if (packageName.equals("com.ridio.app")) return false;
    return isOlaPackage(packageName) || isCaptainRapidoPackage(packageName);
  }

  public static boolean isOlaPackage(String packageName) {
    if (packageName == null) return false;
    return packageName.equals(PKG_OLA_DRIVER)
        || packageName.startsWith("com.olacabs.oladriver");
  }

  /** Rapido Captain (a11y ACTION_CLICK — no Shizuku required). */
  public static boolean isCaptainRapidoPackage(String packageName) {
    if (packageName == null) return false;
    if (packageName.equals("com.ridio.app")) return false;
    return packageName.equals(PKG_RAPIDO_CAPTAIN)
        || packageName.equals(PKG_RAPIDO_CAPTAIN_LEGACY)
        || packageName.startsWith("com.rapido.rider")
        || packageName.startsWith("com.rapido.captain");
  }

  /** Ola needs privileged inject; Rapido uses accessibility click. */
  public static boolean needsShizukuInject(String packageName) {
    return isOlaPackage(packageName);
  }

  /**
   * Who owns this Accept. Never guess Rapido — a wrong default turns Ola
   * into Accessibility clicks that Ola ignores.
   */
  public enum TapTarget {
    NONE,
    RAPIDO,
    OLA
  }

  public static TapTarget tapTargetOf(String packageName) {
    if (packageName == null || packageName.isEmpty()) return TapTarget.NONE;
    if (isOlaPackage(packageName)) return TapTarget.OLA;
    if (isCaptainRapidoPackage(packageName)) return TapTarget.RAPIDO;
    return TapTarget.NONE;
  }

  public static String canonicalPackage(TapTarget target) {
    if (target == TapTarget.OLA) return PKG_OLA_DRIVER;
    if (target == TapTarget.RAPIDO) return PKG_RAPIDO_CAPTAIN;
    return null;
  }

  public static void setMonitoredPackages(Set<String> packages) {
    synchronized (monitoredPackages) {
      monitoredPackages.clear();
      if (packages != null) {
        monitoredPackages.addAll(packages);
      }
    }
  }

  public static Set<String> getMonitoredPackages() {
    synchronized (monitoredPackages) {
      return new HashSet<>(monitoredPackages);
    }
  }

  public static void cacheTapPoint(String packageName, int x, int y) {
    if (packageName == null || x <= 0 || y <= 0) return;
    cachedTapByPackage.put(packageName, new int[] { x, y });
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .putInt("tap_x_" + packageName, x)
          .putInt("tap_y_" + packageName, y)
          .apply();
    }
  }

  public static void cacheOverlayTapPoint(String packageName, int x, int y) {
    if (packageName == null || x <= 0 || y <= 0) return;
    cachedOverlayTapByPackage.put(packageName, new int[] { x, y });
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .putInt("overlay_tap_x_" + packageName, x)
          .putInt("overlay_tap_y_" + packageName, y)
          .apply();
    }
  }

  /** Returns cached array (do not mutate). Avoids per-call int[] alloc on spray path. */
  public static int[] getCachedTapPoint(String packageName) {
    if (packageName == null) return null;
    return cachedTapByPackage.get(packageName);
  }

  /** Returns cached array (do not mutate). */
  public static int[] getOverlayTapPoint(String packageName) {
    if (packageName == null) return null;
    return cachedOverlayTapByPackage.get(packageName);
  }

  /** Cached Accept point only — never invent a blind default. */
  public static int[] getKnownTapPoint(String packageName) {
    return getCachedTapPoint(packageName);
  }

  /** @deprecated Prefer getKnownTapPoint — blind defaults caused random taps. */
  @Deprecated
  public static int[] getRaceTapPoint(String packageName) {
    int[] cached = getCachedTapPoint(packageName);
    if (cached != null) return cached;
    Context ctx = appContext;
    if (ctx == null) return null;
    try {
      WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
      DisplayMetrics real = new DisplayMetrics();
      if (wm != null && wm.getDefaultDisplay() != null) {
        wm.getDefaultDisplay().getRealMetrics(real);
        return new int[] { real.widthPixels / 2, (int) (real.heightPixels * 0.92f) };
      }
    } catch (Exception ignored) {
    }
    DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
    return new int[] { dm.widthPixels / 2, (int) (dm.heightPixels * 0.92f) };
  }

  public static boolean hasCachedTapPoint(String packageName) {
    return packageName != null && cachedTapByPackage.containsKey(packageName);
  }

  /** Drop in-memory + prefs tap caches so post-Accept we never re-spray stale coords. */
  public static void clearCachedTapPoints(String packageName) {
    if (packageName == null) {
      cachedTapByPackage.clear();
      cachedOverlayTapByPackage.clear();
      return;
    }
    cachedTapByPackage.remove(packageName);
    cachedOverlayTapByPackage.remove(packageName);
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .remove("tap_x_" + packageName)
          .remove("tap_y_" + packageName)
          .remove("overlay_tap_x_" + packageName)
          .remove("overlay_tap_y_" + packageName)
          .apply();
    }
  }

  public static boolean tryAcquireClickLock(long debounceMs) {
    long now = System.currentTimeMillis();
    long last = lastClickAt.get();
    if (now - last < debounceMs) {
      return false;
    }
    return lastClickAt.compareAndSet(last, now);
  }
}
