package com.ridio.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Normalized Rapido/Ola offer snapshot. Observational only — never an
 * acceptance decision and never a filter verdict.
 *
 * <p>{@link #confidence} is detection quality (how completely the text was
 * parsed), not the probability the ride will be accepted. Unknown fare or
 * distance stays {@code null}; it is never written as {@code 0}.
 */
public final class OfferObservation {

  public static final String WARN_EMPTY_TEXT = "EMPTY_TEXT";
  public static final String WARN_FARE_UNKNOWN = "FARE_UNKNOWN";
  public static final String WARN_PICKUP_UNKNOWN = "PICKUP_UNKNOWN";
  public static final String WARN_DROP_UNKNOWN = "DROP_UNKNOWN";
  public static final String WARN_AMBIGUOUS_RIDE_ALERT = "AMBIGUOUS_RIDE_ALERT";
  public static final String WARN_SPAM_OR_STATUS = "SPAM_OR_STATUS";
  public static final String WARN_MALFORMED_TEXT = "MALFORMED_TEXT";

  public final PartnerTarget.Id targetId;
  public final String packageName;
  public final String fingerprint;
  public final long observedAtMs;
  public final Double fare;
  public final Float pickupKm;
  public final Float dropKm;
  public final float confidence;
  public final List<String> warnings;
  public final String source;

  OfferObservation(
      PartnerTarget.Id targetId,
      String packageName,
      String fingerprint,
      long observedAtMs,
      Double fare,
      Float pickupKm,
      Float dropKm,
      float confidence,
      List<String> warnings,
      String source) {
    this.targetId = targetId;
    this.packageName = packageName;
    this.fingerprint = fingerprint != null ? fingerprint : "";
    this.observedAtMs = observedAtMs;
    this.fare = fare;
    this.pickupKm = pickupKm;
    this.dropKm = dropKm;
    this.confidence = clamp01(confidence);
    this.warnings = warnings == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(new ArrayList<String>(warnings));
    this.source = source;
  }

  /**
   * Parse notification or card text into a snapshot. Does not apply fare or
   * distance filters and does not record an accept attempt.
   */
  public static OfferObservation fromText(
      String packageName,
      String text,
      String source,
      long observedAtMs) {
    PartnerTarget target = PartnerTargetRegistry.find(packageName);
    PartnerTarget.Id id = target != null ? target.id : null;
    Double fare = OfferTextParser.parseFareOrUnknown(text);
    Float pickup = OfferTextParser.parsePickupKmOrUnknown(text);
    Float drop = OfferTextParser.parseDropKmOrUnknown(text);
    String mark = OfferTextParser.cardMark(text);
    boolean looks = OfferTextParser.looksLikeRideOffer(text);
    boolean spam = OfferTextParser.isSpamText(text);
    boolean empty = text == null || text.trim().isEmpty();
    boolean malformed = !empty && text.indexOf('₹') >= 0 && fare == null;

    List<String> warnings = new ArrayList<>();
    if (empty) warnings.add(WARN_EMPTY_TEXT);
    if (fare == null) warnings.add(WARN_FARE_UNKNOWN);
    if (pickup == null) warnings.add(WARN_PICKUP_UNKNOWN);
    if (drop == null) warnings.add(WARN_DROP_UNKNOWN);
    if (malformed) warnings.add(WARN_MALFORMED_TEXT);
    if (spam) warnings.add(WARN_SPAM_OR_STATUS);
    if (!empty && !looks) warnings.add(WARN_AMBIGUOUS_RIDE_ALERT);
    else if (looks && fare == null && pickup == null && drop == null) {
      warnings.add(WARN_AMBIGUOUS_RIDE_ALERT);
    }

    float confidence = detectionQuality(empty, looks, spam, fare, pickup, drop);
    String fingerprint = fingerprintOf(id, packageName, fare, pickup, drop, mark);
    return new OfferObservation(
        id, packageName, fingerprint, observedAtMs,
        fare, pickup, drop, confidence, warnings, source);
  }

  public boolean sameOffer(OfferObservation other) {
    if (other == null) return false;
    return fingerprint.equals(other.fingerprint);
  }

  /** True when this snapshot is a different offer than {@code previous}. */
  public boolean isNewOfferRelativeTo(OfferObservation previous) {
    if (previous == null) return true;
    return !sameOffer(previous);
  }

  /**
   * Detection completeness only. Filter pass/fail and accept dispatch must
   * not be folded into this number.
   */
  static float detectionQuality(
      boolean empty,
      boolean looksLikeRide,
      boolean spam,
      Double fare,
      Float pickupKm,
      Float dropKm) {
    if (empty) return 0f;
    if (spam && !looksLikeRide) return 0.10f;
    float q = looksLikeRide ? 0.35f : 0.15f;
    if (fare != null) q += 0.25f;
    if (pickupKm != null) q += 0.15f;
    if (dropKm != null) q += 0.10f;
    return clamp01(q);
  }

  static String fingerprintOf(
      PartnerTarget.Id targetId,
      String packageName,
      Double fare,
      Float pickupKm,
      Float dropKm,
      String cardMark) {
    return (targetId != null ? targetId.name() : "?")
        + '|'
        + (packageName != null ? packageName : "")
        + "|f=" + fareToken(fare)
        + "|p=" + distToken(pickupKm)
        + "|d=" + distToken(dropKm)
        + "|m=" + (cardMark != null ? cardMark : "");
  }

  private static String fareToken(Double fare) {
    if (fare == null) return "?";
    return String.format(Locale.US, "%.2f", fare);
  }

  private static String distToken(Float km) {
    if (km == null) return "?";
    return String.format(Locale.US, "%.3f", km);
  }

  private static float clamp01(float v) {
    if (v < 0f) return 0f;
    if (v > 1f) return 1f;
    return v;
  }
}
