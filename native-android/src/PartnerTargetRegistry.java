package com.ridio.app;

/**
 * Package → target lookup. Rapido/Ola rules match the pre-registry engine.
 * Future targets are registered as disabled stubs with no packages.
 */
public final class PartnerTargetRegistry {

  public static final String SELF_PACKAGE = "com.ridio.app";
  public static final String PKG_OLA_DRIVER = "com.olacabs.oladriver";
  public static final String PKG_RAPIDO_CAPTAIN = "com.rapido.rider";
  public static final String PKG_RAPIDO_CAPTAIN_LEGACY = "com.rapido.captain";

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

  /** Empty packages — cannot match, cannot act. */
  private static final PartnerTarget UBER_STUB = stub(PartnerTarget.Id.UBER, "Uber Driver");
  private static final PartnerTarget PORTER_STUB = stub(PartnerTarget.Id.PORTER, "Porter Partner");
  private static final PartnerTarget NAMMA_STUB =
      stub(PartnerTarget.Id.NAMMA_YATRI, "Namma Yatri Partner");

  /** Ola before Rapido — same order as the previous tapTargetOf. */
  private static final PartnerTarget[] TARGETS = {
      OLA, RAPIDO, UBER_STUB, PORTER_STUB, NAMMA_STUB
  };

  private PartnerTargetRegistry() {}

  private static PartnerTarget stub(PartnerTarget.Id id, String displayName) {
    return new PartnerTarget(
        id,
        displayName,
        false,
        PartnerTarget.Interaction.DETECTION_ONLY,
        new String[0],
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
   * First target whose verified packages match. Stubs never match.
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

  public static boolean isTargetPackage(String packageName) {
    if (packageName == null) return false;
    if (SELF_PACKAGE.equals(packageName)) return false;
    return isOlaPackage(packageName) || isCaptainRapidoPackage(packageName);
  }

  /** True only for an enabled Ola match — same as the previous engine. */
  public static boolean needsShizukuInject(String packageName) {
    PartnerTarget t = find(packageName);
    return t != null && t.needsShizuku();
  }

  /**
   * Actionable tap routing only. Stubs and unknown packages are
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
