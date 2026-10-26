package com.ridio.app;

/**
 * Cross-process configuration snapshot. User intent and entitlement are
 * independent; effective enablement is computed, never stored as requested.
 *
 * <p>Freshness policy (F1.1):
 * <ul>
 *   <li>{@code writtenAtMs} is wall-clock time of the last authoritative write.
 *   <li>A readable sidecar is treated as the last local authority, even if old.
 *       It does <em>not</em> prove the subscription server has not revoked since.
 *   <li>If the sidecar is missing or unreadable, a cached snapshot may be used
 *       only when {@code writtenAtMs} is known and {@code now - writtenAtMs}
 *       is at most {@link #DEFAULT_MAX_STALE_MS}.
 *   <li>Unknown freshness ({@code writtenAtMs <= 0}) never authorizes actions.
 *   <li>A later successful read/write recovers the engine; brief IO failures
 *       do not permanently latch OFF.
 * </ul>
 */
public final class ConfigSnapshot {

  public static final String EXTRA_REQUESTED_ENABLED = "requested_enabled";
  public static final String EXTRA_ENTITLEMENT_KNOWN = "entitlement_known";
  public static final String EXTRA_ENTITLEMENT_ACTIVE = "entitlement_active";
  public static final String EXTRA_ENTITLEMENT_DEVICE_ALLOWED = "entitlement_device_allowed";
  public static final String EXTRA_ENTITLEMENT_END_MS = "entitlement_end_ms";
  public static final String EXTRA_CONFIG_REVISION = "config_revision";

  /**
   * When the sidecar cannot be read, deny actions if the last known write is
   * older than this. Not a substitute for server-side revocation.
   */
  public static final long DEFAULT_MAX_STALE_MS = 4L * 60L * 60L * 1000L;

  public final boolean requestedEnabled;
  public final boolean entitlementKnown;
  public final boolean entitlementActive;
  public final boolean deviceAllowed;
  public final long endMs;
  public final long revision;
  /** Epoch ms of last authoritative write. 0 = freshness unknown. */
  public final long writtenAtMs;

  public ConfigSnapshot(
      boolean requestedEnabled,
      boolean entitlementKnown,
      boolean entitlementActive,
      boolean deviceAllowed,
      long endMs,
      long revision) {
    this(requestedEnabled, entitlementKnown, entitlementActive, deviceAllowed,
        endMs, revision, 0L);
  }

  public ConfigSnapshot(
      boolean requestedEnabled,
      boolean entitlementKnown,
      boolean entitlementActive,
      boolean deviceAllowed,
      long endMs,
      long revision,
      long writtenAtMs) {
    this.requestedEnabled = requestedEnabled;
    this.entitlementKnown = entitlementKnown;
    this.entitlementActive = entitlementActive;
    this.deviceAllowed = deviceAllowed;
    this.endMs = Math.max(0L, endMs);
    this.revision = Math.max(0L, revision);
    this.writtenAtMs = Math.max(0L, writtenAtMs);
  }

  public ConfigSnapshot withWrittenAt(long writtenAtMs) {
    return new ConfigSnapshot(
        requestedEnabled, entitlementKnown, entitlementActive, deviceAllowed,
        endMs, revision, writtenAtMs);
  }

  /** Same entitlement rules as the engine: known, active, device, unexpired. */
  public static boolean isEntitled(
      boolean known,
      boolean active,
      boolean deviceAllowed,
      long endMs,
      long nowMs) {
    if (!known) return false;
    if (!active || !deviceAllowed) return false;
    return endMs > 0L && nowMs < endMs;
  }

  public boolean isEntitled(long nowMs) {
    return isEntitled(entitlementKnown, entitlementActive, deviceAllowed, endMs, nowMs);
  }

  public static boolean isEffectivelyEnabled(
      boolean requestedEnabled,
      boolean known,
      boolean active,
      boolean deviceAllowed,
      long endMs,
      long nowMs) {
    return requestedEnabled && isEntitled(known, active, deviceAllowed, endMs, nowMs);
  }

  public boolean isEffectivelyEnabled(long nowMs) {
    return requestedEnabled && isEntitled(nowMs);
  }

  /**
   * Whether a cached snapshot may authorize actions.
   * A readable sidecar is local authority. A missing sidecar is only usable
   * while {@code writtenAtMs} is known and within {@code maxStaleMs}.
   */
  public static boolean isCachedSnapshotActionable(
      boolean sidecarReadable,
      long writtenAtMs,
      long nowMs,
      long maxStaleMs) {
    if (sidecarReadable) return true;
    if (writtenAtMs <= 0L || nowMs < writtenAtMs) return false;
    long max = maxStaleMs > 0L ? maxStaleMs : DEFAULT_MAX_STALE_MS;
    return (nowMs - writtenAtMs) <= max;
  }

  /**
   * Full action gate: freshness + requested + entitlement.
   * Never treats unknown entitlement as valid.
   */
  public static boolean canAuthorizeActions(
      boolean sidecarReadable,
      long writtenAtMs,
      long nowMs,
      long maxStaleMs,
      boolean requestedEnabled,
      boolean known,
      boolean active,
      boolean deviceAllowed,
      long endMs) {
    if (!isCachedSnapshotActionable(sidecarReadable, writtenAtMs, nowMs, maxStaleMs)) {
      return false;
    }
    return isEffectivelyEnabled(requestedEnabled, known, active, deviceAllowed, endMs, nowMs);
  }

  /**
   * Once an F1 snapshot exists, requested intent is the atom only.
   * {@code engineOnFlag} is a bounded fallback when no snapshot is present.
   */
  public static boolean resolveRequestedEnabled(
      boolean requestedAtom,
      boolean engineOnFlag,
      boolean authoritativeSnapshot) {
    if (authoritativeSnapshot) return requestedAtom;
    return requestedAtom || engineOnFlag;
  }

  public static String entitlementBlockReason(
      boolean known,
      boolean active,
      boolean deviceAllowed,
      long endMs,
      long nowMs) {
    if (!known) return "SUBSCRIPTION_REQUIRED";
    if (!deviceAllowed) return "DEVICE_NOT_ALLOWED";
    if (!active) return "SUBSCRIPTION_REQUIRED";
    if (endMs <= 0L) return "SUBSCRIPTION_REQUIRED";
    if (nowMs >= endMs) return "SUBSCRIPTION_EXPIRED";
    return "";
  }

  public String entitlementBlockReason(long nowMs) {
    return entitlementBlockReason(
        entitlementKnown, entitlementActive, deviceAllowed, endMs, nowMs);
  }

  /** Apply incoming when it is a real revision and not older than what we have. */
  public static boolean shouldApplyRevision(long incomingRevision, long appliedRevision) {
    return incomingRevision > 0L && incomingRevision >= appliedRevision;
  }

  /** Sidecar refresh only moves forward. */
  public static boolean isNewerRevision(long incomingRevision, long appliedRevision) {
    return incomingRevision > 0L && incomingRevision > appliedRevision;
  }

  public String serialize() {
    return "v=2\n"
        + "rev=" + revision + "\n"
        + "requested=" + (requestedEnabled ? "1" : "0") + "\n"
        + "known=" + (entitlementKnown ? "1" : "0") + "\n"
        + "active=" + (entitlementActive ? "1" : "0") + "\n"
        + "device=" + (deviceAllowed ? "1" : "0") + "\n"
        + "end=" + endMs + "\n"
        + "written=" + writtenAtMs + "\n";
  }

  /**
   * Parse a sidecar body. Missing entitlement fields fail closed.
   * Missing {@code written} leaves {@code writtenAtMs = 0} (freshness unknown).
   */
  public static ConfigSnapshot parse(String text) {
    if (text == null || text.trim().isEmpty()) return null;
    boolean requested = false;
    boolean known = false;
    boolean active = false;
    boolean device = true;
    long end = 0L;
    long rev = 0L;
    long written = 0L;
    boolean sawField = false;
    String[] lines = text.split("\n");
    for (String raw : lines) {
      if (raw == null) continue;
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int eq = line.indexOf('=');
      if (eq <= 0) continue;
      String key = line.substring(0, eq).trim();
      String val = line.substring(eq + 1).trim();
      if ("v".equals(key)) {
        sawField = true;
        continue;
      }
      if ("rev".equals(key)) {
        rev = parseLong(val, 0L);
        sawField = true;
      } else if ("requested".equals(key)) {
        requested = isTruthy(val);
        sawField = true;
      } else if ("known".equals(key)) {
        known = isTruthy(val);
        sawField = true;
      } else if ("active".equals(key)) {
        active = isTruthy(val);
        sawField = true;
      } else if ("device".equals(key)) {
        device = isTruthy(val);
        sawField = true;
      } else if ("end".equals(key)) {
        end = parseLong(val, 0L);
        sawField = true;
      } else if ("written".equals(key)) {
        written = parseLong(val, 0L);
        sawField = true;
      }
    }
    if (!sawField) return null;
    return new ConfigSnapshot(requested, known, active, device, end, rev, written);
  }

  private static boolean isTruthy(String val) {
    if (val == null) return false;
    return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
  }

  private static long parseLong(String val, long fallback) {
    if (val == null || val.isEmpty()) return fallback;
    try {
      return Long.parseLong(val);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
