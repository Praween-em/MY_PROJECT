package com.ridio.app;

import android.os.Build;

/**
 * OEM label helpers for the Service Reliability checklist.
 * Accept-path telemetry / diagnose reports were removed.
 */
public final class ServiceHealth {

  private ServiceHealth() {}

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
    return s == null ? "" : s.toLowerCase();
  }

  private static boolean containsAny(String m, String b, String... keys) {
    for (String k : keys) {
      if (m.contains(k) || b.contains(k)) return true;
    }
    return false;
  }
}
