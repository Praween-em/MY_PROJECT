package com.ridio.app;

/**
 * Package → target lookup. Rapido/Ola remain the only actionable targets.
 * Uber / Porter / Namma Yatri Partner match Play Store packages for
 * observation only — no prefixes, no click, no Shizuku.
 */
public final class PartnerTargetRegistry {

  public static final String SELF_PACKAGE = "com.ridio.app";
  public static final String PKG_OLA_DRIVER = "com.olacabs.oladriver";
  public static final String PKG_RAPIDO_CAPTAIN = "com.rapido.rider";
  public static final String PKG_RAPIDO_CAPTAIN_LEGACY = "com.rapido.captain";
  /** Play: Uber Driver (Drive and Deliver). Not the rider app {@code com.ubercab}. */
  public static final String PKG_UBER_DRIVER = "com.ubercab.driver";
  /** Play: Porter Driver Partner App. */
  public static final String PKG_PORTER_PARTNER = "com.theporter.android.driverapp";
  /** Play: Namma Yatri Driver Partner. Not the customer app {@code in.juspay.nammayatri}. */
  public static final String PKG_NAMMA_YATRI_PARTNER = "in.juspay.nammayatripartner";

  /** Same actionable set as {@link AutoClickerConfig.TapTarget}. */
  public enum ActionableTap {
    NONE,
    RAPIDO,
    OLA
  }

  private static final PartnerTarget RAPIDO = new PartnerTarget(
      PartnerTarget.Id.RAPIDO,
      "Rapido Captain",
      true,
      PartnerTarget.Interaction.A11Y_CLICK,
      new String[] { PKG_RAPIDO_CAPTAIN, PKG_RAPIDO_CAPTAIN_LEGACY },
      new String[] { "com.rapido.rider", "com.rapido.captain" }
  );

  private static final PartnerTarget OLA = new PartnerTarget(
      PartnerTarget.Id.OLA,
      "Ola Driver",
      true,
      PartnerTarget.Interaction.SHIZUKU_HOLD,
      new String[] { PKG_OLA_DRIVER },
      new String[] { "com.olacabs.oladriver" }
  );

  private static final PartnerTarget UBER = detectionOnly(
      PartnerTarget.Id.UBER, "Uber Driver", PKG_UBER_DRIVER);
  private static final PartnerTarget PORTER = detectionOnly(
      PartnerTarget.Id.PORTER, "Porter Partner", PKG_PORTER_PARTNER);
  private static final PartnerTarget NAMMA_YATRI = detectionOnly(
      PartnerTarget.Id.NAMMA_YATRI, "Namma Yatri Partner", PKG_NAMMA_YATRI_PARTNER);

  /** Ola before Rapido — same order as the previous tapTargetOf. */
  private static final PartnerTarget[] TARGETS = {
      OLA, RAPIDO, UBER, PORTER, NAMMA_YATRI
  };

  private PartnerTargetRegistry() {}

  /** Exact Play package, enabled for matching, never authorized to act. */
  private static PartnerTarget detectionOnly(
      PartnerTarget.Id id, String displayName, String exactPackage) {
    return new PartnerTarget(
        id,
        displayName,
        true,
        PartnerTarget.Interaction.DETECTION_ONLY,
        new String[] { exactPackage },
        new String[0]
    );
  }

  public static PartnerTarget[] all() {
    return TARGETS.clone();
  }

  public static PartnerTarget byId(PartnerTarget.Id id) {
    if (id == null) return null;
    for (PartnerTarget t : TARGETS) {
      if (t.id == id) return t;
    }
    return null;
  }

  /**
   * First target whose verified packages match.
   * {@link #SELF_PACKAGE} is never a partner target.
   */
  public static PartnerTarget find(String packageName) {
    if (packageName == null || packageName.isEmpty()) return null;
    if (SELF_PACKAGE.equals(packageName)) return null;
    for (PartnerTarget t : TARGETS) {
      if (t.matchesPackage(packageName)) return t;
    }
    return null;
  }

  public static boolean isOlaPackage(String packageName) {
    PartnerTarget t = find(packageName);
    return t != null && t.id == PartnerTarget.Id.OLA;
  }

  public static boolean isCaptainRapidoPackage(String packageName) {
    if (packageName == null) return false;
    if (SELF_PACKAGE.equals(packageName)) return false;
    PartnerTarget t = find(packageName);
    return t != null && t.id == PartnerTarget.Id.RAPIDO;
  }

  /**
   * Actionable hunt/accept targets only. Detection-only partners are
   * monitored separately so they cannot inherit Ola/Rapido clicks.
   */
  public static boolean isTargetPackage(String packageName) {
    if (packageName == null) return false;
    if (SELF_PACKAGE.equals(packageName)) return false;
    return isOlaPackage(packageName) || isCaptainRapidoPackage(packageName);
  }

  /** Enabled DETECTION_ONLY match — observe, never click. */
  public static boolean isDetectionPackage(String packageName) {
    PartnerTarget t = find(packageName);
    return t != null
        && t.enabled
        && t.interaction == PartnerTarget.Interaction.DETECTION_ONLY;
  }

  /** True only for an enabled Ola match — same as the previous engine. */
  public static boolean needsShizukuInject(String packageName) {
    PartnerTarget t = find(packageName);
    return t != null && t.needsShizuku();
  }

  /**
   * Actionable tap routing only. Detection-only and unknown packages are
   * {@link ActionableTap#NONE} so they cannot fall through to Rapido/Ola.
   */
  public static ActionableTap actionableTap(String packageName) {
    if (packageName == null || packageName.isEmpty()) return ActionableTap.NONE;
    PartnerTarget t = find(packageName);
    if (t == null || !t.canAct()) return ActionableTap.NONE;
    if (t.id == PartnerTarget.Id.OLA) return ActionableTap.OLA;
    if (t.id == PartnerTarget.Id.RAPIDO) return ActionableTap.RAPIDO;
    return ActionableTap.NONE;
  }
}
