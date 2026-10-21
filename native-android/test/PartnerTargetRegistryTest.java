package com.ridio.app;

/**
 * Patch 0/1: freeze Rapido/Ola package routing and prove stubs cannot act.
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
    testStubsDisabledEmptyAndCannotAct();
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
    assertFalse("unknown no shizuku",
        PartnerTargetRegistry.needsShizukuInject("com.ubercab.driver"));
    assertFalse("null no shizuku", PartnerTargetRegistry.needsShizukuInject(null));
  }

  private static void testSelfPackageNeverATarget() {
    assertFalse("self not rapido",
        PartnerTargetRegistry.isCaptainRapidoPackage("com.ridio.app"));
    assertFalse("self not ola", PartnerTargetRegistry.isOlaPackage("com.ridio.app"));
    assertFalse("self not target", PartnerTargetRegistry.isTargetPackage("com.ridio.app"));
    assertEq("self no tap", PartnerTargetRegistry.ActionableTap.NONE,
        PartnerTargetRegistry.actionableTap("com.ridio.app"));
    assertTrue("find self null", PartnerTargetRegistry.find("com.ridio.app") == null);
  }

  private static void testUnknownPackagesSkipped() {
    String[] unknown = {
        "com.ubercab.driver",
        "com.theporter.android.driverapp",
        "in.juspay.nammayatripartner",
        "in.juspay.nammayatri",
        "com.swiggy.delivery",
        "com.application.zomato",
        "com.android.vending",
        "com.whatsapp"
    };
    for (String pkg : unknown) {
      assertEq("unknown tap " + pkg, PartnerTargetRegistry.ActionableTap.NONE,
          PartnerTargetRegistry.actionableTap(pkg));
      assertFalse("unknown target " + pkg, PartnerTargetRegistry.isTargetPackage(pkg));
      assertFalse("unknown shizuku " + pkg, PartnerTargetRegistry.needsShizukuInject(pkg));
      assertTrue("unknown find " + pkg, PartnerTargetRegistry.find(pkg) == null);
    }
  }

  private static void testStubsDisabledEmptyAndCannotAct() {
    PartnerTarget uber = PartnerTargetRegistry.byId(PartnerTarget.Id.UBER);
    PartnerTarget porter = PartnerTargetRegistry.byId(PartnerTarget.Id.PORTER);
    PartnerTarget namma = PartnerTargetRegistry.byId(PartnerTarget.Id.NAMMA_YATRI);
    assertTrue("uber stub", uber != null && !uber.enabled && !uber.canAct()
        && uber.exactPackages.length == 0 && uber.packagePrefixes.length == 0
        && uber.interaction == PartnerTarget.Interaction.DETECTION_ONLY);
    assertTrue("porter stub", porter != null && !porter.enabled && !porter.canAct()
        && porter.exactPackages.length == 0);
    assertTrue("namma stub", namma != null && !namma.enabled && !namma.canAct()
        && namma.exactPackages.length == 0);
    assertFalse("stub shizuku", uber.needsShizuku());
    assertEq("stub tap none", PartnerTargetRegistry.ActionableTap.NONE,
        PartnerTargetRegistry.actionableTap("com.ubercab.driver"));
  }

  private static void testNullAndEmpty() {
    assertTrue("find null", PartnerTargetRegistry.find(null) == null);
    assertTrue("find empty", PartnerTargetRegistry.find("") == null);
    assertFalse("ola null", PartnerTargetRegistry.isOlaPackage(null));
    assertFalse("rapido null", PartnerTargetRegistry.isCaptainRapidoPackage(null));
    assertFalse("target null", PartnerTargetRegistry.isTargetPackage(null));
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
