package com.ridio.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rapido/Ola text parsers extracted from {@link AutoClickerService}.
 * Legacy methods return {@code 0} when a field is missing (filter fail-closed).
 * Boxed helpers return {@code null} when unknown — never invent a fare.
 */
public final class OfferTextParser {

  static final Pattern PRICE_COMBO =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)\\s*\\+\\s*₹\\s*(\\d+(?:\\.\\d+)?)");
  static final Pattern PRICE_SINGLE =
      Pattern.compile("₹\\s*(\\d+(?:\\.\\d+)?)");
  static final Pattern DIST_ANY =
      Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(k\\s*m|m)\\b", Pattern.CASE_INSENSITIVE);
  static final Pattern PICKUP_DIST =
      Pattern.compile(
          "(?:pick\\s*-?\\s*up|pickup|\u092a\u0940\u0915\u0905\u092a|\u0c2a\u0c3f\u0c15\u0c2a\u0c4d)[^\\d]{0,40}(\\d+(?:\\.\\d+)?)\\s*(k\\s*m|m)\\b",
          Pattern.CASE_INSENSITIVE);
  static final Pattern DROP_DIST =
      Pattern.compile(
          "(?:drop\\s*-?\\s*off|dropoff|drop|destination|\u0921\u094d\u0930\u0949\u092a)[^\\d]{0,40}(\\d+(?:\\.\\d+)?)\\s*(k\\s*m|m)\\b",
          Pattern.CASE_INSENSITIVE);
  static final Pattern PRICE_ANY =
      Pattern.compile("(?:₹|rs\\.?|inr|\u0930\u0941)\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

  private OfferTextParser() {}

  /** Legacy: missing fare is {@code 0}. */
  public static double parseRapidoPrice(String dump) {
    if (dump == null || dump.isEmpty()) return 0;
    Matcher combo = PRICE_COMBO.matcher(dump);
    if (combo.find()) {
      return parseLooseDouble(combo.group(1)) + parseLooseDouble(combo.group(2));
    }
    Matcher single = PRICE_SINGLE.matcher(dump);
    if (single.find()) {
      return parseLooseDouble(single.group(1));
    }
    Matcher any = PRICE_ANY.matcher(dump);
    if (any.find()) return parseLooseDouble(any.group(1));
    return 0;
  }

  /** {@code null} when no fare token is present. */
  public static Double parseFareOrUnknown(String dump) {
    if (dump == null || dump.isEmpty()) return null;
    double v = parseRapidoPrice(dump);
    return v > 0 ? Double.valueOf(v) : null;
  }

  public static float parseDropKm(String dump) {
    if (dump == null || dump.isEmpty()) return 0f;
    Matcher labeled = DROP_DIST.matcher(dump);
    if (labeled.find()) {
      return distToKm(labeled.group(1), labeled.group(2));
    }
    List<Float> kms = parseAllDistancesKm(dump);
    return kms.size() >= 2 ? kms.get(1) : 0f;
  }

  public static Float parseDropKmOrUnknown(String dump) {
    if (dump == null || dump.isEmpty()) return null;
    float v = parseDropKm(dump);
    return v > 0f ? Float.valueOf(v) : null;
  }

  public static String cardMark(String text) {
    if (text == null || text.isEmpty()) return "";
    String stripped = text.toLowerCase(Locale.US)
        .replaceAll("(?:₹|rs\\.?|inr)\\s*\\d+(?:\\.\\d+)?", " ")
        .replaceAll("\\d+(?:\\.\\d+)?\\s*(?:k\\s*m|m)\\b", " ")
        .replaceAll("[^a-z\\u0900-\\u0dff]+", " ")
        .trim();
    if (stripped.length() < 6) return "";
    return stripped.length() > 64 ? stripped.substring(0, 64) : stripped;
  }

  public static float parsePickupKm(String dump) {
    if (dump == null || dump.isEmpty()) return 0f;
    Matcher labeled = PICKUP_DIST.matcher(dump);
    if (labeled.find()) {
      return distToKm(labeled.group(1), labeled.group(2));
    }
    List<Float> kms = parseAllDistancesKm(dump);
    return kms.isEmpty() ? 0f : kms.get(0);
  }

  public static Float parsePickupKmOrUnknown(String dump) {
    if (dump == null || dump.isEmpty()) return null;
    float v = parsePickupKm(dump);
    return v > 0f ? Float.valueOf(v) : null;
  }

  public static List<Float> parseAllDistancesKm(String dump) {
    List<Float> out = new ArrayList<>();
    if (dump == null || dump.isEmpty()) return out;
    Matcher m = DIST_ANY.matcher(dump);
    while (m.find()) {
      float km = distToKm(m.group(1), m.group(2));
      if (km > 0f) out.add(km);
    }
    return out;
  }

  static float distToKm(String number, String unit) {
    double v = parseLooseDouble(number);
    if (v <= 0) return 0f;
    String u = unit != null ? unit.toLowerCase(Locale.US).replace(" ", "") : "km";
    if ("m".equals(u)) {
      if (v > 20000) return 0f;
      return (float) (v / 1000.0);
    }
    return (float) v;
  }

  static double parseLooseDouble(String text) {
    if (text == null) return 0;
    String cleaned = text.replaceAll("[^0-9.]", "");
    if (cleaned.isEmpty() || cleaned.equals(".")) return 0;
    try {
      return Double.parseDouble(cleaned);
    } catch (Exception e) {
      return 0;
    }
  }

  public static boolean dumpHasAcceptCue(String original, String lowerOrSame) {
    String h = lowerOrSame != null ? lowerOrSame : original;
    if (h == null) return false;
    if (h.contains("accept") || h.contains("slide")) return true;
    String src = original != null ? original : h;
    return src.contains("\u0938\u094d\u0935\u0940\u0915\u093e\u0930")
        || src.contains("\u090f\u0915\u094d\u0938\u0947\u092a\u094d\u091f")
        || src.contains("\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a")
        || src.contains("\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a")
        || src.contains("\u0c06\u0c2e\u0c4b\u0c26\u0c3f\u0c02\u0c1a")
        || src.contains("\u0b8f\u0bb1\u0bcd\u0bb1\u0bc1\u0b95\u0bcd\u0b95\u0bca\u0bb3")
        || src.contains("\u0cb8\u0ccd\u0cb5\u0cc0\u0c95\u0cb0\u0cbf\u0cb8")
        || src.contains("\u0ab8\u0acd\u0ab5\u0ac0\u0a95\u0abe\u0ab0")
        || src.contains("\u0997\u09cd\u09b0\u09b9\u09a3");
  }

  public static boolean isSpamText(String hay) {
    if (hay == null || hay.isEmpty()) return false;
    if (hasTripOfferCue(hay)) return false;
    return hay.contains("completed order") || hay.contains("total earning")
        || hay.contains("accepted orders") || hay.contains("login") || hay.contains("otp")
        || hay.contains("password") || hay.contains("wallet") || hay.contains("cashout")
        || hay.contains("payout") || hay.contains("rate your")
        || hay.contains("update available") || hay.contains("battery")
        || hay.contains("document") || hay.contains("training")
        || hay.contains("incentive") || hay.contains("bonus")
        || hay.contains("challenge") || hay.contains("go online") || hay.contains("you're online")
        || hay.contains("you are online") || hay.contains("offline") || hay.contains("duty")
        || hay.contains("kyc") || hay.contains("tip received") || hay.contains("payment received")
        || hay.contains("weekly") || hay.contains("leaderboard") || hay.contains("referral")
        || hay.contains("promotion") || hay.contains("offer ends") || hay.contains("recharge");
  }

  public static boolean hasTripOfferCue(String text) {
    if (text == null || text.isEmpty()) return false;
    String h = text.toLowerCase(Locale.US);
    return h.contains("accept")
        || h.contains("\u0938\u094d\u0935\u0940\u0915\u093e\u0930")
        || h.contains("\u0c05\u0c02\u0c17\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a")
        || h.contains("\u0c38\u0c4d\u0c35\u0c40\u0c15\u0c30\u0c3f\u0c02\u0c1a")
        || h.contains("\u090f\u0915\u094d\u0938\u0947\u092a\u094d\u091f")
        || h.contains("₹")
        || h.contains("new order") || h.contains("new ride") || h.contains("ride request")
        || h.contains("new trip") || h.contains("trip request") || h.contains("new booking")
        || h.contains("new request") || h.contains("incoming trip") || h.contains("incoming request")
        || h.contains("pickup") || h.contains("pick up")
        || h.contains("trip for you") || h.contains("opportunity")
        || h.contains("ride offer") || h.contains("order request")
        || ((h.contains("trip") || h.contains("request") || h.contains("ride"))
            && (h.contains("km") || h.contains("min") || h.contains("cash")
                || h.contains("bike") || h.contains("auto") || h.contains("fare")
                || h.contains("rs") || h.matches(".*\\d.*")))
        || ((h.contains("cash") || h.contains("bike") || h.contains("auto"))
            && (h.contains("km") || h.contains("min") || h.contains("₹") || h.contains("rs")));
  }

  public static boolean isDriverStatusPing(String pkg, String text, boolean ongoing) {
    if (hasTripOfferCue(text)) return false;
    String h = text != null ? text.toLowerCase(Locale.US).trim() : "";
    if (h.contains("you're online") || h.contains("you are online")
        || h.contains("go online") || h.contains("offline")
        || h.contains("login") || h.contains("otp") || h.contains("kyc")
        || h.contains("document") || h.contains("training")
        || h.contains("update available") || h.contains("leaderboard")
        || h.contains("referral") || h.contains("wallet")
        || h.contains("total earning") || h.contains("weekly")
        || h.contains("duty")) {
      return true;
    }
    return false;
  }

  public static boolean looksLikeRideOffer(String text) {
    if (text == null || text.trim().isEmpty()) {
      return false;
    }
    if (hasTripOfferCue(text)) return true;
    String h = text.toLowerCase(Locale.US).trim();
    if (isSpamText(h)) return false;
    if (dumpHasAcceptCue(text, h)) return true;
    if (h.contains("new order") || h.contains("new ride") || h.contains("ride request")
        || h.contains("new trip") || h.contains("trip request") || h.contains("incoming trip")
        || h.contains("new booking") || h.contains("booking request")
        || h.contains("new request") || h.contains("trip alert")
        || h.contains("forwarded") || h.contains("opportunity")
        || h.contains("accept in") || h.contains("1 ride")) {
      return true;
    }
    if (h.contains("incoming") || h.contains("order request") || h.contains("ride offer")) {
      return true;
    }
    if (h.contains("pickup") || h.contains("pick up") || h.contains("drop")) return true;
    if (h.contains("booking") && (h.contains("₹") || h.contains("km") || h.contains("rs"))) {
      return true;
    }
    if (text.contains("\u0928\u092f\u093e \u0911\u0930\u094d\u0921\u0930")
        || text.contains("\u0928\u092f\u0940 \u0930\u093e\u0907\u0921")
        || text.contains("\u092a\u093f\u0915\u0905\u092a")
        || text.contains("\u0930\u093e\u0907\u0921 \u0930\u093f\u0915\u094d\u0935\u0947\u0938\u094d\u091f")
        || text.contains("\u0c15\u0c4a\u0c24\u0c4d\u0c24 \u0c06\u0c30\u0c4d\u0c21\u0c30\u0c4d")
        || text.contains("\u0c15\u0c4a\u0c24\u0c4d\u0c24 \u0c30\u0c48\u0c21\u0c4d")
        || text.contains("\u0c2a\u0c3f\u0c15\u0c2a\u0c4d")) {
      return true;
    }
    if ((h.contains("₹") || h.contains("rs.") || h.contains("rs ") || h.contains("inr"))
        && h.matches(".*\\d.*")) {
      return true;
    }
    if (h.contains("km") && h.matches(".*\\d.*")) return true;
    return false;
  }

  /**
   * Same fail-closed numeric rule as {@code AutoClickerService.evaluateOfferFilters}
   * / {@code notifPassesFilters}. Missing required fields are {@code 0}.
   */
  public static boolean legacyFiltersPass(
      double fare,
      float pickup,
      float drop,
      int minFare,
      int maxFare,
      float minPickup,
      float maxPickup,
      float maxDrop) {
    if ((minFare > 0 || maxFare > 0) && fare <= 0) return false;
    if (minFare > 0 && fare < minFare) return false;
    if (maxFare > 0 && fare > maxFare) return false;
    if ((minPickup > 0f || maxPickup > 0f) && pickup <= 0f) return false;
    if (minPickup > 0f && pickup < minPickup) return false;
    if (maxPickup > 0f && pickup > maxPickup) return false;
    if (maxDrop > 0f && (drop <= 0f || drop > maxDrop)) return false;
    return true;
  }
}
