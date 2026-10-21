package com.ridio.app;

/**
 * Golden tests for extracted Rapido/Ola parsers. Numeric results match the
 * pre-extraction AutoClickerService methods.
 */
public final class OfferTextParserTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    testCurrencyFormats();
    testDecimalsAndCombo();
    testDistances();
    testMissingAndMalformed();
    testRideAlertHeuristics();
    testSpamVsOffer();
    testFilterFailClosed();

    System.out.println();
    System.out.println("OfferTextParserTest passed=" + passed + " failed=" + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  private static void testCurrencyFormats() {
    assertEq("rupee space", 120.0, OfferTextParser.parseRapidoPrice("Accept ₹ 120 Bike"));
    assertEq("rupee tight", 85.0, OfferTextParser.parseRapidoPrice("₹85"));
    assertEq("rupee runbook", 100.0, OfferTextParser.parseRapidoPrice("₹100"));
    assertEq("rs dot", 99.0, OfferTextParser.parseRapidoPrice("Rs. 99 cash"));
    assertEq("inr", 200.0, OfferTextParser.parseRapidoPrice("INR 200"));
    assertEq("hindi ru", 80.0, OfferTextParser.parseRapidoPrice("रु 80"));
    assertEq("boxed known", 120.0, OfferTextParser.parseFareOrUnknown("₹ 120"));
  }

  private static void testDecimalsAndCombo() {
    assertEq("decimal", 149.5, OfferTextParser.parseRapidoPrice("₹ 149.5"));
    assertEq("combo", 140.0, OfferTextParser.parseRapidoPrice("₹120 + ₹20"));
    assertEq("combo spaced", 155.0, OfferTextParser.parseRapidoPrice("₹ 100 + ₹ 55"));
  }

  private static void testDistances() {
    assertEq("pickup km", 0.8f, OfferTextParser.parsePickupKm("pickup 0.8 km drop 4.2 km"));
    assertEq("drop labeled", 4.2f, OfferTextParser.parseDropKm("pickup 0.8 km drop 4.2 km"));
    assertEq("meters to km", 0.8f, OfferTextParser.parsePickupKm("800 m"));
    assertEq("two distances first pickup", 1.2f, OfferTextParser.parsePickupKm("1.2 km 6.0 km"));
    assertEq("two distances second drop", 6.0f, OfferTextParser.parseDropKm("1.2 km 6.0 km"));
    assertEq("one distance no drop", 0f, OfferTextParser.parseDropKm("2.5 km"));
    assertEq("min not metres", 0f, OfferTextParser.parsePickupKm("Accept in 5 min"));
    assertEq("notif one distance", 0.9f, OfferTextParser.parsePickupKm("New ride ₹140 pickup 0.9 km"));
    assertTrue("mark from address",
        OfferTextParser.cardMark("₹120 0.8 km Koramangala 5th block").length() >= 6);
  }

  private static void testMissingAndMalformed() {
    assertEq("empty fare", 0.0, OfferTextParser.parseRapidoPrice(""));
    assertEq("null fare", 0.0, OfferTextParser.parseRapidoPrice(null));
    assertTrue("unknown fare null", OfferTextParser.parseFareOrUnknown("Accept now") == null);
    assertTrue("unknown fare empty", OfferTextParser.parseFareOrUnknown("") == null);
    assertTrue("unknown pickup", OfferTextParser.parsePickupKmOrUnknown("Accept ₹ 90") == null);
    assertTrue("unknown drop", OfferTextParser.parseDropKmOrUnknown("pickup 1 km") == null);
    assertEq("no number fare", 0.0, OfferTextParser.parseRapidoPrice("hello"));
    assertEq("malformed", 0.0, OfferTextParser.parseRapidoPrice("₹ "));
    assertFalse("empty not ride", OfferTextParser.looksLikeRideOffer(""));
    assertFalse("null not ride", OfferTextParser.looksLikeRideOffer(null));
  }

  private static void testRideAlertHeuristics() {
    assertTrue("new ride", OfferTextParser.looksLikeRideOffer("New ride ₹150"));
    assertTrue("accept in", OfferTextParser.looksLikeRideOffer("Accept in 5"));
    assertTrue("hindi order", OfferTextParser.looksLikeRideOffer("नया ऑर्डर"));
    assertTrue("cue accept", OfferTextParser.hasTripOfferCue("Please accept"));
    assertTrue("cue rupee", OfferTextParser.hasTripOfferCue("₹ 80"));
  }

  private static void testSpamVsOffer() {
    assertTrue("otp spam", OfferTextParser.isSpamText("enter otp 1234"));
    assertTrue("earning spam", OfferTextParser.isSpamText("total earning 500"));
    assertFalse("offer not spam", OfferTextParser.isSpamText("new ride accept ₹90"));
    assertTrue("status ping", OfferTextParser.isDriverStatusPing("com.rapido.rider", "you are online", true));
    assertFalse("offer not status",
        OfferTextParser.isDriverStatusPing("com.rapido.rider", "new ride ₹90", false));
  }

  private static void testFilterFailClosed() {
    assertFalse("min fare missing",
        OfferTextParser.legacyFiltersPass(0, 1f, 2f, 80, 0, 0f, 0f, 0f));
    assertFalse("min pickup missing",
        OfferTextParser.legacyFiltersPass(100, 0f, 2f, 0, 0, 1f, 5f, 0f));
    assertTrue("values present pass",
        OfferTextParser.legacyFiltersPass(120, 1.2f, 4f, 80, 200, 0.5f, 5f, 10f));
    assertFalse("fare below min",
        OfferTextParser.legacyFiltersPass(50, 1f, 2f, 80, 0, 0f, 0f, 0f));
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

  private static void assertEq(String name, double expected, Double actual) {
    if (actual != null && Math.abs(expected - actual) < 0.0001) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(String name, double expected, double actual) {
    if (Math.abs(expected - actual) < 0.0001) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
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
}
