package com.ridio.app;

/**
 * One partner-app target. Identity + verified packages + whether this
 * target may act. Detection-only partners match exact Play packages and
 * never authorize a click or Shizuku inject.
 */
public final class PartnerTarget {

  public enum Id {
    RAPIDO,
    OLA,
    UBER,
    PORTER,
    NAMMA_YATRI
  }

  /**
   * What the engine may do. {@link #DETECTION_ONLY} and {@link #NONE} never
   * authorize a click or Shizuku inject.
   */
  public enum Interaction {
    NONE,
    DETECTION_ONLY,
    A11Y_CLICK,
    SHIZUKU_HOLD
  }

  public final Id id;
  public final String displayName;
  public final boolean enabled;
  public final Interaction interaction;
  /** Exact package ids verified in this repo. */
  public final String[] exactPackages;
  /** Existing prefix rules (Ola / Rapido only). Empty for detection-only. */
  public final String[] packagePrefixes;

  PartnerTarget(
      Id id,
      String displayName,
      boolean enabled,
      Interaction interaction,
      String[] exactPackages,
      String[] packagePrefixes) {
    this.id = id;
    this.displayName = displayName;
    this.enabled = enabled;
    this.interaction = interaction != null ? interaction : Interaction.NONE;
    this.exactPackages = exactPackages != null ? exactPackages : new String[0];
    this.packagePrefixes = packagePrefixes != null ? packagePrefixes : new String[0];
  }

  public boolean canAct() {
    if (!enabled) return false;
    return interaction == Interaction.A11Y_CLICK
        || interaction == Interaction.SHIZUKU_HOLD;
  }

  public boolean needsShizuku() {
    return canAct() && interaction == Interaction.SHIZUKU_HOLD;
  }

  boolean matchesPackage(String packageName) {
    if (packageName == null || packageName.isEmpty()) return false;
    for (String exact : exactPackages) {
      if (packageName.equals(exact)) return true;
    }
    for (String prefix : packagePrefixes) {
      if (prefix != null && !prefix.isEmpty() && packageName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
