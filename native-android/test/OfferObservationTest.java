package com.ridio.app;

/**
 * Patch 2: normalized offer model and observational sink.
 *
 * Run: javac -d &lt;out&gt;
 *        src/PartnerTarget.java src/PartnerTargetRegistry.java
 *        src/OfferTextParser.java src/OfferObservation.java
 *        src/OfferObservationSink.java
 *        test/OfferObservationTest.java
 *      java -cp &lt;out&gt; com.ridio.app.OfferObservationTest
 */
public final class OfferObservationTest {

  private static final String RAPIDO = PartnerTargetRegistry.PKG_RAPIDO_CAPTAIN;
  private static final String OLA = PartnerTargetRegistry.PKG_OLA_DRIVER;

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    testUnknownStaysUnknown();
    testCurrencyAndDecimalFixtures();
    testMissingDistances();
    testMalformedNotification();
    testDuplicateEventsKeepSameFingerprint();
    testNewFareIsNewOffer();
    testAmbiguousRideAlert();
    testConfidenceIsDetectionNotAcceptance();
    testSinkIsObservational();
    testNoListenerIsNoOp();

    System.out.println();
    System.out.println("OfferObservationTest passed=" + passed + " failed=" + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  private static void testUnknownStaysUnknown() {
    OfferObservation o = OfferObservation.fromText(
        RAPIDO, "Accept now", "NLS", 1_000L);
    assertTrue("fare null not zero", o.fare == null);
    assertTrue("pickup null not zero", o.pickupKm == null);
    assertTrue("drop null not zero", o.dropKm == null);
    assertTrue("fare warning", o.warnings.contains(OfferObservation.WARN_FARE_UNKNOWN));
    assertTrue("pickup warning", o.warnings.contains(OfferObservation.WARN_PICKUP_UNKNOWN));
    assertTrue("fingerprint uses ?", o.fingerprint.contains("f=?"));
    assertFalse("fingerprint not f=0", o.fingerprint.contains("f=0.00"));
    assertEq("target rapido", PartnerTarget.Id.RAPIDO, o.targetId);
    assertEq("pkg", RAPIDO, o.packageName);
    assertEq("observedAt", 1_000L, o.observedAtMs);
  }

  private static void testCurrencyAndDecimalFixtures() {
    OfferObservation rupee = OfferObservation.fromText(
        RAPIDO, "New ride ₹ 149.5 pickup 0.8 km drop 4.2 km Koramangala", "NLS", 2_000L);
    assertEq("decimal fare", 149.5, rupee.fare);
    assertEq("pickup", 0.8f, rupee.pickupKm);
    assertEq("drop", 4.2f, rupee.dropKm);
    assertTrue("high detection", rupee.confidence >= 0.80f);
    assertFalse("no fare warn", rupee.warnings.contains(OfferObservation.WARN_FARE_UNKNOWN));

    OfferObservation combo = OfferObservation.fromText(
        OLA, "Accept ₹120 + ₹20 1.2 km 6.0 km", "NLS", 2_100L);
    assertEq("combo fare", 140.0, combo.fare);
    assertEq("ola target", PartnerTarget.Id.OLA, combo.targetId);

    OfferObservation rs = OfferObservation.fromText(
        RAPIDO, "Rs. 99 cash", "NLS", 2_200L);
    assertEq("rs fare", 99.0, rs.fare);
  }

  private static void testMissingDistances() {
    OfferObservation one = OfferObservation.fromText(
        RAPIDO, "New ride ₹140 pickup 0.9 km", "NLS", 3_000L);
    assertEq("fare present", 140.0, one.fare);
    assertEq("pickup present", 0.9f, one.pickupKm);
    assertTrue("drop unknown", one.dropKm == null);
    assertTrue("drop warning", one.warnings.contains(OfferObservation.WARN_DROP_UNKNOWN));
    assertTrue("drop token ?", one.fingerprint.contains("d=?"));
    assertFalse("drop not zero token", one.fingerprint.contains("d=0.000"));
  }

  private static void testMalformedNotification() {
    OfferObservation empty = OfferObservation.fromText(RAPIDO, "", "NLS", 4_000L);
    assertTrue("empty fare unknown", empty.fare == null);
    assertTrue("empty warning", empty.warnings.contains(OfferObservation.WARN_EMPTY_TEXT));
    assertEq("empty confidence", 0f, empty.confidence);

    OfferObservation broken = OfferObservation.fromText(RAPIDO, "₹ ", "NLS", 4_100L);
    assertTrue("malformed fare unknown", broken.fare == null);
    assertTrue("malformed warn", broken.warnings.contains(OfferObservation.WARN_MALFORMED_TEXT));

    OfferObservation nullText = OfferObservation.fromText(OLA, null, "NLS", 4_200L);
    assertTrue("null fare unknown", nullText.fare == null);
    assertEq("null conf", 0f, nullText.confidence);
  }

  private static void testDuplicateEventsKeepSameFingerprint() {
    OfferObservation a = OfferObservation.fromText(
        RAPIDO, "Accept ₹120 0.8 km 4.2 km Indiranagar", "NLS", 5_000L);
    OfferObservation b = OfferObservation.fromText(
        RAPIDO, "Accept ₹120 0.8 km 4.2 km Indiranagar", "NLS_POST", 5_050L);
    assertTrue("same offer", a.sameOffer(b));
    assertFalse("duplicate is not new", a.isNewOfferRelativeTo(b));
    assertEq("same fingerprint", a.fingerprint, b.fingerprint);
    assertTrue("times differ", a.observedAtMs != b.observedAtMs);
  }

  private static void testNewFareIsNewOffer() {
    OfferObservation first = OfferObservation.fromText(
        RAPIDO, "Accept ₹120 0.8 km 4.2 km Indiranagar", "NLS", 6_000L);
    OfferObservation next = OfferObservation.fromText(
        RAPIDO, "Accept ₹155 0.8 km 4.2 km Indiranagar", "NLS", 6_100L);
    assertTrue("new fare is new offer", next.isNewOfferRelativeTo(first));
    assertFalse("fingerprints differ", first.sameOffer(next));

    OfferObservation newPickup = OfferObservation.fromText(
        RAPIDO, "Accept ₹120 2.1 km 4.2 km Indiranagar", "NLS", 6_200L);
    assertTrue("new pickup is new offer", newPickup.isNewOfferRelativeTo(first));
  }

  private static void testAmbiguousRideAlert() {
    OfferObservation alert = OfferObservation.fromText(
        RAPIDO, "Accept in 5", "NLS", 7_000L);
    assertTrue("looks like ride", OfferTextParser.looksLikeRideOffer("Accept in 5"));
    assertTrue("fare unknown", alert.fare == null);
    assertTrue("ambiguous warn", alert.warnings.contains(OfferObservation.WARN_AMBIGUOUS_RIDE_ALERT));
    assertTrue("mid confidence", alert.confidence > 0.2f && alert.confidence < 0.6f);

    OfferObservation status = OfferObservation.fromText(
        RAPIDO, "you are online", "NLS", 7_100L);
    assertTrue("status spam", status.warnings.contains(OfferObservation.WARN_SPAM_OR_STATUS));
    assertTrue("status not ride", status.warnings.contains(OfferObservation.WARN_AMBIGUOUS_RIDE_ALERT));
  }

  private static void testConfidenceIsDetectionNotAcceptance() {
    OfferObservation rich = OfferObservation.fromText(
        RAPIDO, "New ride ₹200 pickup 1.0 km drop 5.0 km Whitefield", "NLS", 8_000L);
    assertTrue("rich detection", rich.confidence >= 0.80f);
    assertFalse("legacy filter would fail max fare",
        OfferTextParser.legacyFiltersPass(200, 1f, 5f, 0, 150, 0f, 0f, 0f));
    assertTrue("observation is not an accept verdict", rich.source.equals("NLS"));
    assertTrue("no accepted field", !hasField("accepted"));
    assertTrue("no confirmed field", !hasField("confirmed"));
    assertTrue("no attempt field", !hasField("attemptCount"));
  }

  private static boolean hasField(String name) {
    try {
      OfferObservation.class.getDeclaredField(name);
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  private static void testSinkIsObservational() {
    OfferObservationSink.Recorder rec = OfferObservationSink.installRecorder();
    try {
      OfferObservationSink.publishFromText(
          RAPIDO, "Accept ₹90 0.5 km 3.0 km", "NLS", 9_000L);
      OfferObservationSink.publishFromText(
          RAPIDO, "Accept ₹90 0.5 km 3.0 km", "NLS", 9_010L);
      OfferObservationSink.publishFromText(
          RAPIDO, "Accept ₹110 0.5 km 3.0 km", "NLS", 9_020L);
      assertEq("three publishes", 3, rec.size());
      assertTrue("first two duplicate identity", rec.get(0).sameOffer(rec.get(1)));
      assertTrue("third is new fare", rec.get(2).isNewOfferRelativeTo(rec.get(0)));
      assertTrue("unknown never stored as 0", rec.get(0).fare != null && rec.get(0).fare > 0);
    } finally {
      OfferObservationSink.clear();
    }
  }

  private static void testNoListenerIsNoOp() {
    OfferObservationSink.clear();
    OfferObservationSink.publishFromText(RAPIDO, "Accept ₹70", "NLS", 10_000L);
    OfferObservationSink.publish(null);
    assertTrue("no listener survives", true);
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

  private static void assertEq(String name, Object expected, Object actual) {
    if (expected == null ? actual == null : expected.equals(actual)) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(String name, long expected, long actual) {
    assertTrue(name, expected == actual);
  }

  private static void assertEq(String name, int expected, int actual) {
    assertTrue(name, expected == actual);
  }

  private static void assertEq(String name, float expected, float actual) {
    if (Math.abs(expected - actual) < 0.0001f) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(String name, double expected, Double actual) {
    if (actual != null && Math.abs(expected - actual) < 0.0001) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(String name, float expected, Float actual) {
    if (actual != null && Math.abs(expected - actual) < 0.0001f) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }
}
