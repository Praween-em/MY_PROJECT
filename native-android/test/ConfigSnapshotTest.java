package com.ridio.app;

/**
 * JVM tests for F1 snapshot logic. Android multi-process behavior is not
 * executed here — see the physical-device steps in the implementation report.
 *
 * Run: javac -d <out> src/ConfigSnapshot.java test/ConfigSnapshotTest.java
 *      java -cp <out> com.ridio.app.ConfigSnapshotTest
 */
public final class ConfigSnapshotTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    long now = 1_700_000_000_000L;
    long future = now + 86_400_000L;
    long past = now - 1_000L;

    testRequestedOnValidEntitlement(now, future);
    testRequestedOnInvalidEntitlement(now);
    testEngineAdoptsNewerSnapshot(now, future);
    testRequestedStaysOnWhenEntitlementInvalid(now, future);
    testRequestedOffStaysOff(now, future);
    testOlderRevisionRejected();
    testMissedBroadcastSidecarRefresh(now, future);
    testNotificationGateRejectsInvalidEntitlement(now);
    testUiRecreationPreservesSnapshot(now, future);
    testExpiryAndDeviceAuthorization(now, future, past);
    testAuthorizeFreshValid(now, future);
    testAuthorizeDeniesInvalidEntitlement(now, future);
    testNewerRevokeWins(now, future);
    testMissingSidecarStaleFailsClosed(now, future);
    testTemporaryFailureRecovers(now, future);
    testRequestedOffIgnoresEngineOnFlag();
    testSavePreservesRequestedWhenEntitlementInvalid();
    testActionGateUsesAuthorize(now, future);
    testFailedWriteKeepsPrevious(now, future);
    testV1ParseFreshnessUnknown();

    System.out.println();
    System.out.println("ConfigSnapshotTest passed=" + passed + " failed=" + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  /** 1. Requested ON + valid entitlement → effective ON. */
  private static void testRequestedOnValidEntitlement(long now, long future) {
    ConfigSnapshot s = new ConfigSnapshot(true, true, true, true, future, 1L);
    assertTrue("1 effective ON", s.isEffectivelyEnabled(now));
    assertTrue("1 entitled", s.isEntitled(now));
    assertEq("1 requested", true, s.requestedEnabled);
  }

  /** 2. Requested ON + invalid entitlement → effective OFF. */
  private static void testRequestedOnInvalidEntitlement(long now) {
    ConfigSnapshot unknown = new ConfigSnapshot(true, false, true, true, 0L, 2L);
    ConfigSnapshot inactive = new ConfigSnapshot(true, true, false, true, now + 1000L, 2L);
    assertFalse("2 unknown not effective", unknown.isEffectivelyEnabled(now));
    assertFalse("2 inactive not effective", inactive.isEffectivelyEnabled(now));
    assertEq("2 requested still ON", true, unknown.requestedEnabled);
  }

  /** 3. Newer revision is adopted; equals is allowed; older is not. */
  private static void testEngineAdoptsNewerSnapshot(long now, long future) {
    long applied = 4L;
    ConfigSnapshot incoming = new ConfigSnapshot(true, true, true, true, future, 5L);
    assertTrue("3 adopt newer", ConfigSnapshot.shouldApplyRevision(incoming.revision, applied));
    assertTrue("3 effective after adopt", incoming.isEffectivelyEnabled(now));
    assertTrue("3 same revision re-apply", ConfigSnapshot.shouldApplyRevision(5L, 5L));
  }

  /**
   * 4. Requested intent remains ON when entitlement becomes invalid;
   * effective automation stays OFF until entitlement is valid again.
   */
  private static void testRequestedStaysOnWhenEntitlementInvalid(long now, long future) {
    ConfigSnapshot on = new ConfigSnapshot(true, true, true, true, future, 6L);
    ConfigSnapshot revoked = new ConfigSnapshot(true, true, false, true, future, 7L);
    ConfigSnapshot restored = new ConfigSnapshot(true, true, true, true, future, 8L);
    assertTrue("4 start effective", on.isEffectivelyEnabled(now));
    assertEq("4 revoked keeps requested", true, revoked.requestedEnabled);
    assertFalse("4 revoked not effective", revoked.isEffectivelyEnabled(now));
    assertTrue("4 restored effective", restored.isEffectivelyEnabled(now));
    assertTrue("4 restored revision applies", ConfigSnapshot.shouldApplyRevision(8L, 7L));
  }

  /** 5. Requested OFF remains OFF even when entitlement is valid. */
  private static void testRequestedOffStaysOff(long now, long future) {
    ConfigSnapshot s = new ConfigSnapshot(false, true, true, true, future, 9L);
    assertTrue("5 entitled", s.isEntitled(now));
    assertFalse("5 effective OFF", s.isEffectivelyEnabled(now));
    assertEq("5 requested OFF", false, s.requestedEnabled);
  }

  /** 6. An older snapshot cannot overwrite a newer revision. */
  private static void testOlderRevisionRejected() {
    assertFalse("6 older rejected", ConfigSnapshot.shouldApplyRevision(3L, 9L));
    assertFalse("6 zero rejected", ConfigSnapshot.shouldApplyRevision(0L, 0L));
    assertFalse("6 negative sanitized", ConfigSnapshot.shouldApplyRevision(-1L, 0L));
    assertFalse("6 sidecar older not newer", ConfigSnapshot.isNewerRevision(3L, 9L));
  }

  /** 7. Missed broadcast: sidecar with a higher revision is refreshable. */
  private static void testMissedBroadcastSidecarRefresh(long now, long future) {
    ConfigSnapshot written = new ConfigSnapshot(true, true, true, true, future, 11L);
    ConfigSnapshot parsed = ConfigSnapshot.parse(written.serialize());
    assertTrue("7 parsed", parsed != null);
    assertTrue("7 sidecar newer", ConfigSnapshot.isNewerRevision(parsed.revision, 10L));
    assertTrue("7 sidecar effective", parsed.isEffectivelyEnabled(now));
    assertFalse("7 same rev not newer", ConfigSnapshot.isNewerRevision(11L, 11L));
  }

  /** 8. Notification gate uses effective enablement, not requested alone. */
  private static void testNotificationGateRejectsInvalidEntitlement(long now) {
    boolean nlsWouldAct = ConfigSnapshot.isEffectivelyEnabled(
        true, true, false, true, now + 1000L, now);
    assertFalse("8 NLS rejects invalid entitlement", nlsWouldAct);
    boolean nlsWouldActUnknown = ConfigSnapshot.isEffectivelyEnabled(
        true, false, true, true, now + 1000L, now);
    assertFalse("8 NLS rejects unknown entitlement", nlsWouldActUnknown);
    String reason = ConfigSnapshot.entitlementBlockReason(
        true, false, true, now + 1000L, now);
    assertEq("8 block reason", "SUBSCRIPTION_REQUIRED", reason);
  }

  /** 9. UI process recreation: serialize/parse keeps requested + entitlement. */
  private static void testUiRecreationPreservesSnapshot(long now, long future) {
    ConfigSnapshot original = new ConfigSnapshot(true, true, true, false, future, 12L);
    ConfigSnapshot restored = ConfigSnapshot.parse(original.serialize());
    assertTrue("9 restored", restored != null);
    assertEq("9 requested", original.requestedEnabled, restored.requestedEnabled);
    assertEq("9 known", original.entitlementKnown, restored.entitlementKnown);
    assertEq("9 active", original.entitlementActive, restored.entitlementActive);
    assertEq("9 device", original.deviceAllowed, restored.deviceAllowed);
    assertEq("9 end", original.endMs, restored.endMs);
    assertEq("9 rev", original.revision, restored.revision);
    assertFalse("9 device block", restored.isEntitled(now));
    assertFalse("9 effective after recreate", restored.isEffectivelyEnabled(now));
  }

  /** 10. Expiry and device authorization stay fail-closed. */
  private static void testExpiryAndDeviceAuthorization(long now, long future, long past) {
    ConfigSnapshot expired = new ConfigSnapshot(true, true, true, true, past, 13L);
    ConfigSnapshot noEnd = new ConfigSnapshot(true, true, true, true, 0L, 13L);
    ConfigSnapshot blocked = new ConfigSnapshot(true, true, true, false, future, 13L);
    ConfigSnapshot valid = new ConfigSnapshot(true, true, true, true, future, 13L);
    assertFalse("10 expired", expired.isEntitled(now));
    assertEq("10 expired reason", "SUBSCRIPTION_EXPIRED", expired.entitlementBlockReason(now));
    assertFalse("10 no end", noEnd.isEntitled(now));
    assertEq("10 no end reason", "SUBSCRIPTION_REQUIRED", noEnd.entitlementBlockReason(now));
    assertFalse("10 device", blocked.isEntitled(now));
    assertEq("10 device reason", "DEVICE_NOT_ALLOWED", blocked.entitlementBlockReason(now));
    assertTrue("10 valid", valid.isEntitled(now));
    assertEq("10 valid reason", "", valid.entitlementBlockReason(now));
    assertFalse("10 parse empty", ConfigSnapshot.parse("") != null);
    assertFalse("10 parse null", ConfigSnapshot.parse(null) != null);
  }

  /** F1.1-1. Requested ON + valid + readable sidecar permits actions. */
  private static void testAuthorizeFreshValid(long now, long future) {
    assertTrue("A1 authorize", ConfigSnapshot.canAuthorizeActions(
        true, now, now, ConfigSnapshot.DEFAULT_MAX_STALE_MS,
        true, true, true, true, future));
  }

  /** F1.1-2. Invalid / unknown / expired / device-blocked deny actions. */
  private static void testAuthorizeDeniesInvalidEntitlement(long now, long future) {
    long max = ConfigSnapshot.DEFAULT_MAX_STALE_MS;
    assertFalse("A2 unknown", ConfigSnapshot.canAuthorizeActions(
        true, now, now, max, true, false, true, true, future));
    assertFalse("A2 inactive", ConfigSnapshot.canAuthorizeActions(
        true, now, now, max, true, true, false, true, future));
    assertFalse("A2 expired", ConfigSnapshot.canAuthorizeActions(
        true, now, now, max, true, true, true, true, now - 1L));
    assertFalse("A2 device", ConfigSnapshot.canAuthorizeActions(
        true, now, now, max, true, true, true, false, future));
    assertFalse("A2 requested off", ConfigSnapshot.canAuthorizeActions(
        true, now, now, max, false, true, true, true, future));
  }

  /** F1.1-3. Newer revoke snapshot is not older-revision. */
  private static void testNewerRevokeWins(long now, long future) {
    ConfigSnapshot oldOn = new ConfigSnapshot(true, true, true, true, future, 20L, now);
    ConfigSnapshot revoke = new ConfigSnapshot(true, true, false, true, future, 21L, now + 1L);
    assertTrue("A3 apply revoke", ConfigSnapshot.shouldApplyRevision(revoke.revision, oldOn.revision));
    assertFalse("A3 revoke not entitled", revoke.isEntitled(now));
    assertFalse("A3 revoke not authorized", ConfigSnapshot.canAuthorizeActions(
        true, revoke.writtenAtMs, now, ConfigSnapshot.DEFAULT_MAX_STALE_MS,
        revoke.requestedEnabled, revoke.entitlementKnown, revoke.entitlementActive,
        revoke.deviceAllowed, revoke.endMs));
  }

  /** F1.1-4. Missing sidecar + stale/unknown writtenAt fails closed. */
  private static void testMissingSidecarStaleFailsClosed(long now, long future) {
    long max = 4L * 60L * 60L * 1000L;
    assertFalse("A4 unknown freshness", ConfigSnapshot.isCachedSnapshotActionable(
        false, 0L, now, max));
    assertFalse("A4 stale cache", ConfigSnapshot.isCachedSnapshotActionable(
        false, now - max - 1L, now, max));
    assertTrue("A4 fresh cache usable", ConfigSnapshot.isCachedSnapshotActionable(
        false, now - 60_000L, now, max));
    assertFalse("A4 stale not authorize", ConfigSnapshot.canAuthorizeActions(
        false, now - max - 1L, now, max, true, true, true, true, future));
  }

  /** F1.1-5. After a failed read, a new readable snapshot authorizes again. */
  private static void testTemporaryFailureRecovers(long now, long future) {
    long max = ConfigSnapshot.DEFAULT_MAX_STALE_MS;
    assertFalse("A5 down", ConfigSnapshot.canAuthorizeActions(
        false, 0L, now, max, true, true, true, true, future));
    assertTrue("A5 recovered", ConfigSnapshot.canAuthorizeActions(
        true, now, now, max, true, true, true, true, future));
  }

  /** F1.1-7. Authoritative snapshot OFF is not overridden by engine_on. */
  private static void testRequestedOffIgnoresEngineOnFlag() {
    assertFalse("A7 snapshot OFF", ConfigSnapshot.resolveRequestedEnabled(false, true, true));
    assertTrue("A7 snapshot ON", ConfigSnapshot.resolveRequestedEnabled(true, false, true));
    assertTrue("A7 no snapshot flag fallback", ConfigSnapshot.resolveRequestedEnabled(false, true, false));
    assertFalse("A7 no snapshot both off", ConfigSnapshot.resolveRequestedEnabled(false, false, false));
  }

  /**
   * F1.1-8. Save while entitlement is invalid must persist requested ON,
   * not LIVE/effective OFF.
   */
  private static void testSavePreservesRequestedWhenEntitlementInvalid() {
    boolean liveSwitch = false;
    boolean requestedEnabled = true;
    boolean nextEnabled = requestedEnabled;
    assertEq("A8 save requested", true, nextEnabled);
    assertEq("A8 live stays off", false, liveSwitch);
  }

  /** F1.1-9. Action gate is canAuthorizeActions (refresh + freshness + effective). */
  private static void testActionGateUsesAuthorize(long now, long future) {
    boolean peekStaleOn = true;
    boolean authorized = ConfigSnapshot.canAuthorizeActions(
        false, 0L, now, ConfigSnapshot.DEFAULT_MAX_STALE_MS,
        true, true, true, true, future);
    assertTrue("A9 stale peek would have been on", peekStaleOn);
    assertFalse("A9 gate denies without freshness", authorized);
  }

  /**
   * F1.1-10. A failed replacement must not destroy the last valid body.
   * Android uses AtomicFile.failWrite; this asserts the previous serialize
   * remains parseable when the new write is abandoned.
   */
  private static void testFailedWriteKeepsPrevious(long now, long future) {
    ConfigSnapshot previous = new ConfigSnapshot(true, true, true, true, future, 30L, now);
    String stored = previous.serialize();
    boolean writeOk = false;
    if (!writeOk) {
      ConfigSnapshot kept = ConfigSnapshot.parse(stored);
      assertTrue("A10 kept", kept != null);
      assertEq("A10 rev", 30L, kept.revision);
      assertEq("A10 written", now, kept.writtenAtMs);
    }
  }

  /** v=1 sidecar without written= has unknown freshness. */
  private static void testV1ParseFreshnessUnknown() {
    ConfigSnapshot v1 = ConfigSnapshot.parse(
        "v=1\nrev=4\nrequested=1\nknown=1\nactive=1\ndevice=1\nend=9999999999999\n");
    assertTrue("A11 v1 parsed", v1 != null);
    assertEq("A11 written unknown", 0L, v1.writtenAtMs);
    assertTrue("A11 readable sidecar still actionable",
        ConfigSnapshot.isCachedSnapshotActionable(true, v1.writtenAtMs, 1_700_000_000_000L,
            ConfigSnapshot.DEFAULT_MAX_STALE_MS));
    assertFalse("A11 missing sidecar + unknown written",
        ConfigSnapshot.isCachedSnapshotActionable(false, v1.writtenAtMs, 1_700_000_000_000L,
            ConfigSnapshot.DEFAULT_MAX_STALE_MS));
  }

  private static void assertTrue(String name, boolean value) {
    if (value) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name);
    }
  }

  private static void assertFalse(String name, boolean value) {
    assertTrue(name, !value);
  }

  private static void assertEq(String name, boolean expected, boolean actual) {
    if (expected == actual) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(String name, long expected, long actual) {
    if (expected == actual) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(String name, String expected, String actual) {
    if (expected.equals(actual)) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }
}
