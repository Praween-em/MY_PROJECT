package com.ridio.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.util.Calendar;
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
  private static final String KEY_CONFIG_REVISION = "config_revision";
  private static final String KEY_MONITORED_PACKAGES = "monitored_packages";
  private static final String KEY_ENTITLEMENT_KNOWN = "entitlement_known";
  private static final String KEY_ENTITLEMENT_ACTIVE = "entitlement_active";
  private static final String KEY_ENTITLEMENT_DEVICE_ALLOWED = "entitlement_device_allowed";
  private static final String KEY_ENTITLEMENT_END_MS = "entitlement_end_ms";

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
  private static final AtomicInteger maxFare = new AtomicInteger(0);
  private static final AtomicInteger delayMs = new AtomicInteger(0);
  private static final AtomicInteger filterMode = new AtomicInteger(MODE_PRICE);
  private static final AtomicReference<Float> minPickup = new AtomicReference<>(0f);
  private static final AtomicReference<Float> maxPickup = new AtomicReference<>(0f);
  private static final AtomicReference<Float> maxDrop = new AtomicReference<>(0f);
  private static final AtomicLong lastClickAt = new AtomicLong(0);
  private static final AtomicInteger acceptedCount = new AtomicInteger(0);
  private static final AtomicInteger lastLatencyMs = new AtomicInteger(-1);
  private static final AtomicInteger acceptedDay = new AtomicInteger(0);
  private static final AtomicLong lastAcceptRecordUptime = new AtomicLong(0);
  private static final AtomicBoolean entitlementKnown = new AtomicBoolean(false);
  private static final AtomicBoolean entitlementActive = new AtomicBoolean(true);
  private static final AtomicBoolean entitlementDeviceAllowed = new AtomicBoolean(true);
  private static final AtomicLong entitlementEndMs = new AtomicLong(0L);
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

  @SuppressWarnings("deprecation")
  private static int prefsMode() {
    return Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS;
  }

  public static void init(Context context) {
    if (context == null) return;
    appContext = context.getApplicationContext();
    prefs = appContext.getSharedPreferences(PREFS, prefsMode());
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
    // A first install is recorded as known and inactive.
    // An older install stays off until the app receives a server snapshot.
    if (!prefs.contains(KEY_ENTITLEMENT_KNOWN) && !enabled.get() && !enabledFlagExists()) {
      entitlementKnown.set(true);
      entitlementActive.set(false);
      prefs.edit()
          .putBoolean(KEY_ENTITLEMENT_KNOWN, true)
          .putBoolean(KEY_ENTITLEMENT_ACTIVE, false)
          .commit();
    }
  }

  /** Re-read disk prefs into memory (NLS/a11y can start before JS Module). */
  public static void reloadFromPrefs() {
    SharedPreferences p = prefs;
    if (p == null && appContext != null) {
      prefs = appContext.getSharedPreferences(PREFS, prefsMode());
      p = prefs;
    }
    if (p == null) return;
    Set<String> persistedPackages = p.getStringSet(KEY_MONITORED_PACKAGES, null);
    if (persistedPackages != null && !persistedPackages.isEmpty()) {
      synchronized (monitoredPackages) {
        monitoredPackages.clear();
        monitoredPackages.addAll(persistedPackages);
      }
    }
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
    minPickup.set(p.getFloat("min_pickup", 0f));
    maxPickup.set(p.getFloat("max_pickup", 0f));
    maxDrop.set(p.getFloat("max_drop", 0f));
    maxFare.set(Math.max(0, p.getInt("max_fare", 0)));
    continuousForegroundTap.set(false); // HARD OFF — ignore stale pref
    minPrice.set(Math.max(0, p.getInt("min_price", 0)));
    nuclearMode.set(true);
    enabled.set(p.getBoolean("enabled", false) || enabledFlagExists());
    delayMs.set(0);
    loadAcceptStats(p);
    entitlementKnown.set(p.getBoolean(KEY_ENTITLEMENT_KNOWN, false));
    entitlementActive.set(p.getBoolean(KEY_ENTITLEMENT_ACTIVE, true));
    entitlementDeviceAllowed.set(p.getBoolean(KEY_ENTITLEMENT_DEVICE_ALLOWED, true));
    entitlementEndMs.set(p.getLong(KEY_ENTITLEMENT_END_MS, 0L));
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
    return isRequestedEnabled() && isEntitled();
  }

  public static void setEnabled(boolean value) {
    enabled.set(value);
    SharedPreferences p = prefs;
    if (p != null) p.edit().putBoolean("enabled", value).commit(); // commit — a11y must see it now
    writeEnabledFlag(value);
  }

  /** In-memory only read — does not re-hit disk (safe after setEnabled / broadcast). */
  public static boolean peekEnabled() {
    return isRequestedEnabled() && isEntitled();
  }

  /** User intent, independent from permission/entitlement gates. */
  public static boolean isRequestedEnabled() {
    return enabled.get() || enabledFlagExists();
  }

  /**
   * The engine stays off until the server has confirmed this install's plan.
   */
  public static boolean isEntitled() {
    if (!entitlementKnown.get()) return false;
    if (!entitlementActive.get() || !entitlementDeviceAllowed.get()) return false;
    long end = entitlementEndMs.get();
    return end > 0L && System.currentTimeMillis() < end;
  }

  public static String entitlementBlockReason() {
    if (!entitlementKnown.get()) return "SUBSCRIPTION_REQUIRED";
    if (!entitlementDeviceAllowed.get()) return "DEVICE_NOT_ALLOWED";
    if (!entitlementActive.get()) return "SUBSCRIPTION_REQUIRED";
    long end = entitlementEndMs.get();
    if (end <= 0L) return "SUBSCRIPTION_REQUIRED";
    if (System.currentTimeMillis() >= end) return "SUBSCRIPTION_EXPIRED";
    return "";
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
      appContext.getSharedPreferences(PREFS, prefsMode())
          .edit().putBoolean("enabled", value).commit();
      prefs = appContext.getSharedPreferences(PREFS, prefsMode());
    }
    writeEnabledFlag(value);
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
    enabled.set(p.getBoolean("enabled", false) || enabledFlagExists());
    nuclearMode.set(true);
    minPrice.set(Math.max(0, p.getInt("min_price", 0)));
    maxFare.set(Math.max(0, p.getInt("max_fare", 0)));
    minPickup.set(p.getFloat("min_pickup", 0f));
    maxPickup.set(p.getFloat("max_pickup", 0f));
    maxDrop.set(p.getFloat("max_drop", maxDrop.get()));
    entitlementKnown.set(p.getBoolean(KEY_ENTITLEMENT_KNOWN, entitlementKnown.get()));
    entitlementActive.set(p.getBoolean(KEY_ENTITLEMENT_ACTIVE, entitlementActive.get()));
    entitlementDeviceAllowed.set(p.getBoolean(KEY_ENTITLEMENT_DEVICE_ALLOWED, entitlementDeviceAllowed.get()));
    entitlementEndMs.set(p.getLong(KEY_ENTITLEMENT_END_MS, entitlementEndMs.get()));
    loadAcceptStats(p);
  }

  public static void applyFiltersFromBroadcast(
      int minFare, int nextMaxFare, float nextMinPickup, float pickupKm, float nextMaxDrop) {
    minPrice.set(Math.max(0, minFare));
    maxFare.set(Math.max(0, nextMaxFare));
    minPickup.set(Math.max(0f, nextMinPickup));
    maxPickup.set(Math.max(0f, pickupKm));
    maxDrop.set(Math.max(0f, nextMaxDrop));
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .putInt("min_price", minPrice.get())
          .putInt("max_fare", maxFare.get())
          .putFloat("min_pickup", minPickup.get())
          .putFloat("max_pickup", maxPickup.get())
          .putFloat("max_drop", maxDrop.get())
          .commit();
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

  public static int getMaxFare() {
    syncCriticalFromDisk();
    return Math.max(0, maxFare.get());
  }

  public static float getMinPickup() {
    syncCriticalFromDisk();
    return Math.max(0f, minPickup.get());
  }

  public static boolean hasOfferFilters() {
    return getMinPrice() > 0 || getMaxFare() > 0
        || getMinPickup() > 0f || getMaxPickup() > 0f || getMaxDrop() > 0f;
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
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit().putStringSet(KEY_MONITORED_PACKAGES, new HashSet<>(getMonitoredPackages())).commit();
    }
  }

  public static Set<String> getMonitoredPackages() {
    synchronized (monitoredPackages) {
      return new HashSet<>(monitoredPackages);
    }
  }

  /**
   * Monotonic configuration version shared by the UI and the isolated engine
   * process. A single version lets the service reject partial/stale updates.
   */
  public static long getConfigRevision() {
    SharedPreferences p = prefs;
    return p != null ? p.getLong(KEY_CONFIG_REVISION, 0L) : 0L;
  }

  /**
   * Atomically apply every engine setting. Do not replace this with individual
   * setters: the Accessibility process can observe those writes between calls.
   */
  public static synchronized long applyAutomationConfig(
      boolean nextEnabled,
      int nextMinPrice,
      int nextMaxFare,
      float nextMinPickup,
      float nextMaxPickup,
      float nextMaxDrop,
      Set<String> nextPackages) {
    if (prefs == null && appContext != null) init(appContext);
    SharedPreferences p = prefs;
    int fare = Math.max(0, nextMinPrice);
    int fareCap = Math.max(0, nextMaxFare);
    float pickupMin = Math.max(0f, nextMinPickup);
    float pickup = Math.max(0f, nextMaxPickup);
    float dropCap = Math.max(0f, nextMaxDrop);
    Set<String> packages = new HashSet<>();
    if (nextPackages != null) packages.addAll(nextPackages);
    if (packages.isEmpty()) {
      packages.add(PKG_OLA_DRIVER);
      packages.add(PKG_RAPIDO_CAPTAIN);
      packages.add(PKG_RAPIDO_CAPTAIN_LEGACY);
    }
    long revision = p != null ? p.getLong(KEY_CONFIG_REVISION, 0L) + 1L : 1L;

    enabled.set(nextEnabled);
    nuclearMode.set(true);
    delayMs.set(0);
    minPrice.set(fare);
    maxFare.set(fareCap);
    minPickup.set(pickupMin);
    maxPickup.set(pickup);
    maxDrop.set(dropCap);
    synchronized (monitoredPackages) {
      monitoredPackages.clear();
      monitoredPackages.addAll(packages);
    }
    if (p != null) {
      p.edit()
          .putBoolean("enabled", nextEnabled)
          .putBoolean("nuclear_mode", true)
          .putInt("delay_ms", 0)
          .putInt("min_price", fare)
          .putInt("max_fare", fareCap)
          .putFloat("min_pickup", pickupMin)
          .putFloat("max_pickup", pickup)
          .putFloat("max_drop", dropCap)
          .putStringSet(KEY_MONITORED_PACKAGES, new HashSet<>(packages))
          .putLong(KEY_CONFIG_REVISION, revision)
          .commit();
    }
    writeEnabledFlag(nextEnabled);
    markBroadcastApplied();
    return revision;
  }

  /** Persist server-confirmed entitlement for the isolated engine process. */
  public static synchronized void applyEntitlementSnapshot(
      boolean active, boolean deviceAllowed, long subscriptionEndEpochMs) {
    if (prefs == null && appContext != null) init(appContext);
    entitlementKnown.set(true);
    entitlementActive.set(active);
    entitlementDeviceAllowed.set(deviceAllowed);
    entitlementEndMs.set(Math.max(0L, subscriptionEndEpochMs));
    SharedPreferences p = prefs;
    if (p != null) {
      p.edit()
          .putBoolean(KEY_ENTITLEMENT_KNOWN, true)
          .putBoolean(KEY_ENTITLEMENT_ACTIVE, active)
          .putBoolean(KEY_ENTITLEMENT_DEVICE_ALLOWED, deviceAllowed)
          .putLong(KEY_ENTITLEMENT_END_MS, Math.max(0L, subscriptionEndEpochMs))
          .commit();
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

  /** Today's accepted-ride count (survives UI kill; engine writes, Home reads). */
  public static int getAcceptedCount() {
    syncAcceptDay();
    return Math.max(0, acceptedCount.get());
  }

  public static int getLastLatencyMs() {
    return lastLatencyMs.get();
  }

  /**
   * Count a confirmed Accept once per race. Debounces restrikes (~600ms) so
   * ride 2/3 still increment while VERIFY retries do not.
   */
  public static int recordAccept(int latencyMs) {
    syncAcceptDay();
    long now = SystemClock.uptimeMillis();
    long prev = lastAcceptRecordUptime.get();
    if (prev > 0 && now - prev < 600L) {
      if (latencyMs >= 0 && (lastLatencyMs.get() < 0 || latencyMs < lastLatencyMs.get())) {
        lastLatencyMs.set(latencyMs);
        persistAcceptStats();
      }
      return acceptedCount.get();
    }
    if (!lastAcceptRecordUptime.compareAndSet(prev, now)) {
      return acceptedCount.get();
    }
    int n = acceptedCount.incrementAndGet();
    if (latencyMs >= 0) lastLatencyMs.set(latencyMs);
    persistAcceptStats();
    return n;
  }

  private static int todayKey() {
    Calendar c = Calendar.getInstance();
    return c.get(Calendar.YEAR) * 10000
        + (c.get(Calendar.MONTH) + 1) * 100
        + c.get(Calendar.DAY_OF_MONTH);
  }

  private static void syncAcceptDay() {
    int today = todayKey();
    if (acceptedDay.get() == today) return;
    acceptedDay.set(today);
    acceptedCount.set(0);
    persistAcceptStats();
  }

  private static void loadAcceptStats(SharedPreferences p) {
    if (p == null) return;
    int today = todayKey();
    int day = p.getInt("accept_day", 0);
    acceptedDay.set(day);
    if (day != today) {
      acceptedCount.set(0);
      lastLatencyMs.set(-1);
      acceptedDay.set(today);
      persistAcceptStats();
      return;
    }
    acceptedCount.set(Math.max(0, p.getInt("accept_count", 0)));
    lastLatencyMs.set(p.getInt("last_latency_ms", -1));
  }

  private static void persistAcceptStats() {
    SharedPreferences p = prefs;
    if (p == null) return;
    p.edit()
        .putInt("accept_day", acceptedDay.get())
        .putInt("accept_count", acceptedCount.get())
        .putInt("last_latency_ms", lastLatencyMs.get())
        .commit();
  }

  /** Disk flag so {@code :engine} still sees ON after a Recents swipe / process reuse. */
  private static File enabledFlagFile() {
    Context ctx = appContext;
    if (ctx == null) return null;
    return new File(ctx.getFilesDir(), "engine_on");
  }

  private static volatile boolean enabledFlagValue = false;
  private static volatile long enabledFlagAtMs = 0;

  private static boolean enabledFlagExists() {
    long now = SystemClock.uptimeMillis();
    if (enabledFlagAtMs > 0 && now - enabledFlagAtMs < 400L) return enabledFlagValue;
    File f = enabledFlagFile();
    boolean v = f != null && f.exists();
    enabledFlagValue = v;
    enabledFlagAtMs = now;
    return v;
  }

  private static void writeEnabledFlag(boolean on) {
    File f = enabledFlagFile();
    enabledFlagValue = on;
    enabledFlagAtMs = SystemClock.uptimeMillis();
    if (f == null) return;
    try {
      if (on) {
        if (!f.exists()) {
          //noinspection ResultOfMethodCallIgnored
          f.createNewFile();
        }
      } else if (f.exists()) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
      }
    } catch (Exception ignored) {
    }
  }
}
