package com.rapido.tap;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Accept-engine health timestamps.
 * Written by {@code :engine}, read by the UI process via a plain file
 * (SharedPreferences caches do not sync across processes → false "no a11y events").
 */
public final class ServiceHealth {

  private static final String TAG = "ServiceHealth";
  private static final String SNAP_NAME = "service_health.snap";

  private ServiceHealth() {}

  public static volatile boolean serviceConnected = false;
  public static volatile long connectedAtMs = 0L;
  public static volatile long disconnectedAtMs = 0L;
  public static volatile long lastA11yEventMs = 0L;
  public static volatile long lastTargetAppEventMs = 0L;
  public static volatile long lastRideDetectedMs = 0L;
  public static volatile long lastAcceptDetectedMs = 0L;
  public static volatile long lastClickAttemptMs = 0L;
  public static volatile long lastClickSuccessMs = 0L;
  public static volatile long lastClickFailMs = 0L;
  public static volatile String lastPhase = "IDLE";
  public static volatile String lastTargetPkg = "";
  public static volatile String lastDiagnoseCode = "OK";
  public static volatile String lastDiagnoseMessage = "Service looks healthy.";

  private static volatile Context appContext;
  private static volatile long lastA11ySnapWriteMs = 0L;
  private static final Object SNAP_LOCK = new Object();

  public static void init(Context context) {
    if (context == null) return;
    appContext = context.getApplicationContext();
    hydrateFromDisk();
  }

  private static File snapFile() {
    Context ctx = appContext;
    if (ctx == null && AutoClickerConfig.getAppContext() != null) {
      appContext = AutoClickerConfig.getAppContext();
      ctx = appContext;
    }
    if (ctx == null) return null;
    return new File(ctx.getFilesDir(), SNAP_NAME);
  }

  /** Always re-read the snap file — bypasses any process-local cache. */
  public static void hydrateFromDisk() {
    File f = snapFile();
    if (f == null || !f.exists()) return;
    synchronized (SNAP_LOCK) {
      try (BufferedReader br = new BufferedReader(
          new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          int eq = line.indexOf('=');
          if (eq <= 0) continue;
          String k = line.substring(0, eq);
          String v = line.substring(eq + 1);
          switch (k) {
            case "connected":
              serviceConnected = "1".equals(v) || "true".equalsIgnoreCase(v);
              break;
            case "connectedAt":
              connectedAtMs = parseLong(v, connectedAtMs);
              break;
            case "disconnectedAt":
              disconnectedAtMs = parseLong(v, disconnectedAtMs);
              break;
            case "a11y":
              lastA11yEventMs = parseLong(v, lastA11yEventMs);
              break;
            case "target":
              lastTargetAppEventMs = parseLong(v, lastTargetAppEventMs);
              break;
            case "ride":
              lastRideDetectedMs = parseLong(v, lastRideDetectedMs);
              break;
            case "accept":
              lastAcceptDetectedMs = parseLong(v, lastAcceptDetectedMs);
              break;
            case "clickTry":
              lastClickAttemptMs = parseLong(v, lastClickAttemptMs);
              break;
            case "clickOk":
              lastClickSuccessMs = parseLong(v, lastClickSuccessMs);
              break;
            case "clickFail":
              lastClickFailMs = parseLong(v, lastClickFailMs);
              break;
            case "phase":
              if (v != null && !v.isEmpty()) lastPhase = v;
              break;
            case "pkg":
              lastTargetPkg = v != null ? v : "";
              break;
            default:
              break;
          }
        }
      } catch (Exception e) {
        Log.w(TAG, "hydrate fail: " + e.getMessage());
      }
    }
  }

  private static long parseLong(String v, long fallback) {
    try {
      return Long.parseLong(v.trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  private static void persist() {
    File f = snapFile();
    if (f == null) return;
    StringBuilder sb = new StringBuilder(256);
    sb.append("connected=").append(serviceConnected ? "1" : "0").append('\n');
    sb.append("connectedAt=").append(connectedAtMs).append('\n');
    sb.append("disconnectedAt=").append(disconnectedAtMs).append('\n');
    sb.append("a11y=").append(lastA11yEventMs).append('\n');
    sb.append("target=").append(lastTargetAppEventMs).append('\n');
    sb.append("ride=").append(lastRideDetectedMs).append('\n');
    sb.append("accept=").append(lastAcceptDetectedMs).append('\n');
    sb.append("clickTry=").append(lastClickAttemptMs).append('\n');
    sb.append("clickOk=").append(lastClickSuccessMs).append('\n');
    sb.append("clickFail=").append(lastClickFailMs).append('\n');
    sb.append("phase=").append(lastPhase != null ? lastPhase : "IDLE").append('\n');
    sb.append("pkg=").append(lastTargetPkg != null ? lastTargetPkg : "").append('\n');
    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    synchronized (SNAP_LOCK) {
      File tmp = new File(f.getParentFile(), SNAP_NAME + ".tmp");
      try (FileOutputStream fos = new FileOutputStream(tmp)) {
        fos.write(bytes);
        fos.getFD().sync();
      } catch (Exception e) {
        Log.w(TAG, "persist fail: " + e.getMessage());
        return;
      }
      if (!tmp.renameTo(f)) {
        // renameTo can fail across filesystems — fall back to overwrite
        try (FileOutputStream fos = new FileOutputStream(f)) {
          fos.write(bytes);
          fos.getFD().sync();
        } catch (Exception e) {
          Log.w(TAG, "persist overwrite fail: " + e.getMessage());
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
      }
    }
  }

  public static void onConnected() {
    ensureCtx();
    serviceConnected = true;
    connectedAtMs = SystemClock.uptimeMillis();
    persist();
  }

  public static void onDisconnected() {
    ensureCtx();
    serviceConnected = false;
    disconnectedAtMs = SystemClock.uptimeMillis();
    persist();
  }

  public static void onA11yEvent() {
    ensureCtx();
    lastA11yEventMs = SystemClock.uptimeMillis();
    // Throttle disk — a11y fires very often
    if (lastA11yEventMs - lastA11ySnapWriteMs < 500L) return;
    lastA11ySnapWriteMs = lastA11yEventMs;
    persist();
  }

  public static void onTargetAppEvent(String pkg) {
    ensureCtx();
    lastTargetAppEventMs = SystemClock.uptimeMillis();
    if (pkg != null && !pkg.isEmpty()) lastTargetPkg = pkg;
    persist();
  }

  public static void onRideDetected() {
    ensureCtx();
    lastRideDetectedMs = SystemClock.uptimeMillis();
    persist();
  }

  public static void onAcceptDetected() {
    ensureCtx();
    lastAcceptDetectedMs = SystemClock.uptimeMillis();
    persist();
  }

  public static void onClickAttempt() {
    ensureCtx();
    lastClickAttemptMs = SystemClock.uptimeMillis();
    persist();
  }

  public static void onClickSuccess() {
    ensureCtx();
    lastClickSuccessMs = SystemClock.uptimeMillis();
    persist();
  }

  public static void onClickFail() {
    ensureCtx();
    lastClickFailMs = SystemClock.uptimeMillis();
    persist();
  }

  public static void onPhase(String phase) {
    ensureCtx();
    if (phase != null) lastPhase = phase;
    persist();
  }

  private static void ensureCtx() {
    if (appContext == null && AutoClickerConfig.getAppContext() != null) {
      appContext = AutoClickerConfig.getAppContext();
    }
  }

  /**
   * Case codes (user-facing diagnose):
   * A a11y stopped · B no events · C no ride signal · D ride, no Accept ·
   * E Accept, click failed · F after success, next ride missed · OK healthy
   */
  public static String diagnose(boolean a11yEnabled, boolean masterEnabled) {
    return diagnose(a11yEnabled, masterEnabled, true);
  }

  public static String diagnose(
      boolean a11yEnabled,
      boolean masterEnabled,
      boolean notificationListenerEnabled
  ) {
    hydrateFromDisk();
    long now = SystemClock.uptimeMillis();
    if (!a11yEnabled) {
      return setDiag("A", "Accessibility service stopped.");
    }
    // Do not trust stale serviceConnected=false from an old snap when Settings says ON
    if (masterEnabled
        && (lastA11yEventMs == 0L || now - lastA11yEventMs > 90_000L)) {
      return setDiag("B", "Service connected, but no accessibility events are being received.");
    }
    if (lastRideDetectedMs > 0
        && lastAcceptDetectedMs < lastRideDetectedMs
        && now - lastRideDetectedMs < 45_000L) {
      return setDiag("D", "Ride detected, Accept control not found.");
    }
    if (lastClickAttemptMs > 0
        && lastClickSuccessMs < lastClickAttemptMs
        && (lastClickFailMs >= lastClickAttemptMs || now - lastClickAttemptMs < 45_000L)
        && lastAcceptDetectedMs > 0
        && lastClickAttemptMs >= lastAcceptDetectedMs) {
      return setDiag("E", "Accept detected, click action failed.");
    }
    if (lastClickSuccessMs > 0
        && lastRideDetectedMs > lastClickSuccessMs
        && lastAcceptDetectedMs < lastRideDetectedMs
        && now - lastRideDetectedMs < 60_000L) {
      return setDiag("F", "Previous acceptance succeeded; subsequent ride detection failed.");
    }
    // Accept already seen via Captain UI — do not report false "ride detection failed"
    if (lastAcceptDetectedMs > 0 || lastClickSuccessMs > 0) {
      return setDiag("OK", "Service looks healthy.");
    }
    if (masterEnabled
        && lastA11yEventMs > 0
        && lastRideDetectedMs == 0L
        && connectedAtMs > 0
        && now - connectedAtMs > 120_000L) {
      if (!notificationListenerEnabled) {
        return setDiag("C",
            "No ride signal yet. Turn ON Notification access for SUPER RIDEX, then wait for a Rapido offer.");
      }
      return setDiag("C",
          "No ride signal yet. Keep Auto-accept ON, open Rapido Captain, allow notifications, then wait for an offer.");
    }
    return setDiag("OK", "Service looks healthy.");
  }

  private static String setDiag(String code, String message) {
    lastDiagnoseCode = code;
    lastDiagnoseMessage = message;
    return code;
  }

  public static String detectOemId() {
    String m = safe(Build.MANUFACTURER);
    String b = safe(Build.BRAND);
    String fp = safe(Build.FINGERPRINT) + " " + safe(Build.DISPLAY);
    if (containsAny(m, b, "xiaomi", "redmi", "poco") || fp.contains("miui") || fp.contains("hyperos")) {
      return "xiaomi";
    }
    if (containsAny(m, b, "oppo") || fp.contains("coloros")) return "oppo";
    if (containsAny(m, b, "vivo", "iqoo") || fp.contains("funtouch") || fp.contains("originos")) {
      return "vivo";
    }
    if (containsAny(m, b, "realme") || fp.contains("realmeui")) return "realme";
    if (containsAny(m, b, "oneplus") || fp.contains("oxygen")) return "oneplus";
    if (containsAny(m, b, "samsung")) return "samsung";
    if (containsAny(m, b, "motorola", "moto")) return "motorola";
    if (containsAny(m, b, "google", "pixel") || fp.contains("pixel")) return "pixel";
    if (containsAny(m, b, "infinix", "tecno", "itel", "transsion")) return "infinix";
    return "generic";
  }

  public static String detectOemLabel() {
    switch (detectOemId()) {
      case "xiaomi": return "Xiaomi / Redmi / POCO (MIUI / HyperOS)";
      case "oppo": return "OPPO (ColorOS)";
      case "vivo": return "Vivo / iQOO (Funtouch / OriginOS)";
      case "realme": return "realme (realme UI)";
      case "oneplus": return "OnePlus (OxygenOS / ColorOS)";
      case "samsung": return "Samsung (One UI)";
      case "motorola": return "Motorola";
      case "pixel": return "Google Pixel / Stock Android";
      case "infinix": return "Infinix / Tecno / itel (XOS / HiOS)";
      default: return "Android (" + Build.MANUFACTURER + ")";
    }
  }

  private static String safe(String s) {
    return s == null ? "" : s.toLowerCase(Locale.US);
  }

  private static boolean containsAny(String m, String b, String... keys) {
    for (String k : keys) {
      if (m.contains(k) || b.contains(k)) return true;
    }
    return false;
  }

  public static long ageMs(long at) {
    if (at <= 0L) return -1L;
    return Math.max(0L, SystemClock.uptimeMillis() - at);
  }
}
