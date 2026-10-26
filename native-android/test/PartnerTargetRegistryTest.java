package com.ridio.app;

/**
 * Freeze Rapido/Ola action routing and prove detection-only partners
 * match Play packages without inheriting clicks.
 *
 * Run: javac -d &lt;out&gt; src/PartnerTarget.java src/PartnerTargetRegistry.java
 *           test/PartnerTargetRegistryTest.java
 *      java -cp &lt;out&gt; com.ridio.app.PartnerTargetRegistryTest
 */
public final class PartnerTargetRegistryTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    testRapidoExactPackages();
    testRapidoPrefixes();
    testOlaExactAndPrefix();
    testOlaBeforeRapidoOrder();
    testNeedsShizukuOlaOnly();
    testSelfPackageNeverATarget();
    testUnknownPackagesSkipped();
    testDetectionOnlyPartnersMatchButCannotAct();
    testCustomerAppsDoNotMatch();
    testNoPrefixLeakOnDetectionPartners();
    testNullAndEmpty();

    System.out.println();
    System.out.println("PartnerTargetRegistryTest passed=" + passed + " failed=" + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  private static void testRapidoExactPackages() {
    assertEq("rapido rider tap", PartnerTargetRegistry.ActionableTap.RAPIDO,
        PartnerTargetRegistry.actionableTap("com.rapido.rider"));
    assertEq("rapido captain tap", PartnerTargetRegistry.ActionableTap.RAPIDO,
        PartnerTargetRegistry.actionableTap("com.rapido.captain"));
    assertTrue("rapido rider match", PartnerTargetRegistry.isCaptainRapidoPackage("com.rapido.rider"));
    assertTrue("rapido captain match",
        PartnerTargetRegistry.isCaptainRapidoPackage("com.rapido.captain"));
    assertTrue("rapido is target", PartnerTargetRegistry.isTargetPackage("com.rapido.rider"));
    assertFalse("rapido not ola", PartnerTargetRegistry.isOlaPackage("com.rapido.rider"));
    assertFalse("rapido not detection",
        PartnerTargetRegistry.isDetectionPackage("com.rapido.rider"));
  }

  private static void testRapidoPrefixes() {
    assertTrue("rider prefix",
        PartnerTargetRegistry.isCaptainRapidoPackage("com.rapido.rider.foo"));
    assertTrue("captain prefix",
        PartnerTargetRegistry.isCaptainRapidoPackage("com.rapido.captain.debug"));
    assertEq("prefix tap rapido", PartnerTargetRegistry.ActionableTap.RAPIDO,
        PartnerTargetRegistry.actionableTap("com.rapido.rider.foo"));
  }

  private static void testOlaExactAndPrefix() {
    assertEq("ola tap", PartnerTargetRegistry.ActionableTap.OLA,
        PartnerTargetRegistry.actionableTap("com.olacabs.oladriver"));
    assertTrue("ola exact", PartnerTargetRegistry.isOlaPackage("com.olacabs.oladriver"));
    assertTrue("ola prefix", PartnerTargetRegistry.isOlaPackage("com.olacabs.oladriver.staging"));
    assertEq("ola prefix tap", PartnerTargetRegistry.ActionableTap.OLA,
        PartnerTargetRegistry.actionableTap("com.olacabs.oladriver.staging"));
    assertTrue("ola is target", PartnerTargetRegistry.isTargetPackage("com.olacabs.oladriver"));
    assertFalse("ola not rapido",
        PartnerTargetRegistry.isCaptainRapidoPackage("com.olacabs.oladriver"));
    assertFalse("ola not detection",
        PartnerTargetRegistry.isDetectionPackage("com.olacabs.oladriver"));
  }

  private static void testOlaBeforeRapidoOrder() {
    PartnerTarget t = PartnerTargetRegistry.find("com.olacabs.oladriver");
    assertTrue("find ola", t != null && t.id == PartnerTarget.Id.OLA);
  }

  private static void testNeedsShizukuOlaOnly() {
    assertTrue("ola shizuku", PartnerTargetRegistry.needsShizukuInject("com.olacabs.oladriver"));
    assertTrue("ola prefix shizuku",
        PartnerTargetRegistry.needsShizukuInject("com.olacabs.oladriver.x"));
    assertFalse("rapido no shizuku",
        PartnerTargetRegistry.needsShizukuInject("com.rapido.rider"));
    assertFalse("uber no shizuku",
        PartnerTargetRegistry.needsShizukuInject("com.ubercab.driver"));
    assertFalse("porter no shizuku",
        PartnerTargetRegistry.needsShizukuInject("com.theporter.android.driverapp"));
    assertFalse("namma no shizuku",
        PartnerTargetRegistry.needsShizukuInject("in.juspay.nammayatripartner"));
    assertFalse("null no shizuku", PartnerTargetRegistry.needsShizukuInject(null));
  }

  private static void testSelfPackageNeverATarget() {
    assertFalse("self not rapido",
        PartnerTargetRegistry.isCaptainRapidoPackage("com.ridio.app"));
    assertFalse("self not ola", PartnerTargetRegistry.isOlaPackage("com.ridio.app"));
    assertFalse("self not target", PartnerTargetRegistry.isTargetPackage("com.ridio.app"));
    assertFalse("self not detection", PartnerTargetRegistry.isDetectionPackage("com.ridio.app"));
    assertEq("self no tap", PartnerTargetRegistry.ActionableTap.NONE,
        PartnerTargetRegistry.actionableTap("com.ridio.app"));
    assertTrue("find self null", PartnerTargetRegistry.find("com.ridio.app") == null);
  }

  private static void testUnknownPackagesSkipped() {
    String[] unknown = {
        "in.juspay.nammayatri",
        "com.ubercab",
        "com.swiggy.delivery",
        "com.application.zomato",
        "com.android.vending",
        "com.whatsapp"
    };
    for (String pkg : unknown) {
      assertEq("unknown tap " + pkg, PartnerTargetRegistry.ActionableTap.NONE,
          PartnerTargetRegistry.actionableTap(pkg));
      assertFalse("unknown target " + pkg, PartnerTargetRegistry.isTargetPackage(pkg));
      assertFalse("unknown detection " + pkg, PartnerTargetRegistry.isDetectionPackage(pkg));
      assertFalse("unknown shizuku " + pkg, PartnerTargetRegistry.needsShizukuInject(pkg));
      assertTrue("unknown find " + pkg, PartnerTargetRegistry.find(pkg) == null);
    }
  }

  private static void testDetectionOnlyPartnersMatchButCannotAct() {
    assertDetectionPartner(
        PartnerTarget.Id.UBER,
        PartnerTargetRegistry.PKG_UBER_DRIVER,
        "Uber Driver");
    assertDetectionPartner(
        PartnerTarget.Id.PORTER,
        PartnerTargetRegistry.PKG_PORTER_PARTNER,
        "Porter Partner");
    assertDetectionPartner(
        PartnerTarget.Id.NAMMA_YATRI,
        PartnerTargetRegistry.PKG_NAMMA_YATRI_PARTNER,
        "Namma Yatri Partner");
  }

  private static void assertDetectionPartner(
      PartnerTarget.Id id, String pkg, String displayName) {
    PartnerTarget t = PartnerTargetRegistry.byId(id);
    assertTrue(displayName + " registered", t != null);
    assertTrue(displayName + " enabled", t.enabled);
    assertTrue(displayName + " detection mode",
        t.interaction == PartnerTarget.Interaction.DETECTION_ONLY);
    assertTrue(displayName + " cannot act", !t.canAct());
    assertTrue(displayName + " no shizuku", !t.needsShizuku());
    assertTrue(displayName + " exact package",
        t.exactPackages.length == 1 && pkg.equals(t.exactPackages[0]));
    assertTrue(displayName + " no prefixes", t.packagePrefixes.length == 0);

    PartnerTarget found = PartnerTargetRegistry.find(pkg);
    assertTrue(displayName + " find", found != null && found.id == id);
    assertTrue(displayName + " is detection", PartnerTargetRegistry.isDetectionPackage(pkg));
    assertFalse(displayName + " not target", PartnerTargetRegistry.isTargetPackage(pkg));
    assertFalse(displayName + " not ola", PartnerTargetRegistry.isOlaPackage(pkg));
    assertFalse(displayName + " not rapido",
        PartnerTargetRegistry.isCaptainRapidoPackage(pkg));
    assertEq(displayName + " tap none", PartnerTargetRegistry.ActionableTap.NONE,
        PartnerTargetRegistry.actionableTap(pkg));
  }

  private static void testCustomerAppsDoNotMatch() {
    assertTrue("namma customer unknown",
        PartnerTargetRegistry.find("in.juspay.nammayatri") == null);
    assertTrue("uber rider unknown",
        PartnerTargetRegistry.find("com.ubercab") == null);
    assertFalse("namma customer not detection",
        PartnerTargetRegistry.isDetectionPackage("in.juspay.nammayatri"));
  }

  private static void testNoPrefixLeakOnDetectionPartners() {
    String[] leaked = {
        "com.ubercab.driver.staging",
        "com.ubercab.driverapp",
        "com.theporter.android.driverapp.debug",
        "com.theporter.android",
        "in.juspay.nammayatripartner.staging",
        "in.juspay.nammayatripartnerx"
    };
    for (String pkg : leaked) {
      assertTrue("no prefix find " + pkg, PartnerTargetRegistry.find(pkg) == null);
      assertFalse("no prefix detection " + pkg,
          PartnerTargetRegistry.isDetectionPackage(pkg));
      assertEq("no prefix tap " + pkg, PartnerTargetRegistry.ActionableTap.NONE,
          PartnerTargetRegistry.actionableTap(pkg));
    }
  }

  private static void testNullAndEmpty() {
    assertTrue("find null", PartnerTargetRegistry.find(null) == null);
    assertTrue("find empty", PartnerTargetRegistry.find("") == null);
    assertFalse("ola null", PartnerTargetRegistry.isOlaPackage(null));
    assertFalse("rapido null", PartnerTargetRegistry.isCaptainRapidoPackage(null));
    assertFalse("target null", PartnerTargetRegistry.isTargetPackage(null));
    assertFalse("detection null", PartnerTargetRegistry.isDetectionPackage(null));
    assertEq("tap null", PartnerTargetRegistry.ActionableTap.NONE,
        PartnerTargetRegistry.actionableTap(null));
    assertEq("tap empty", PartnerTargetRegistry.ActionableTap.NONE,
        PartnerTargetRegistry.actionableTap(""));
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
    if (expected == actual || (expected != null && expected.equals(actual))) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }
}
