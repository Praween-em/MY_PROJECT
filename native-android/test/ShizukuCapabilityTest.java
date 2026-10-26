package com.ridio.app;

/**
 * JVM tests for F2 Shizuku capability reset. Does not execute the Android
 * binder or IInputManager.
 *
 * Run: javac -d &lt;out&gt; src/ShizukuCapability.java test/ShizukuCapabilityTest.java
 *      java -cp &lt;out&gt; com.ridio.app.ShizukuCapabilityTest
 */
public final class ShizukuCapabilityTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    testUnavailableResetsOnBinderReceived();
    testBinderDeathFailsFastThenReconnectRetries();
    testAttachClearsStaleUnavailable();
    testStaleGenerationAbortsInFlight();
    testFailedInjectDoesNotDisableAfterReconnect();
    testBlockedGenerationDoesNotDisableLaterAttempts();

    System.out.println();
    System.out.println("ShizukuCapabilityTest passed=" + passed + " failed=" + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  /** 1. A latched unavailable state clears when the binder returns. */
  private static void testUnavailableResetsOnBinderReceived() {
    ShizukuCapability c = new ShizukuCapability();
    c.onDirectUnavailable();
    assertFalse("1 blocked while unavailable", c.shouldAttemptDirectInject());
    int before = c.generation();
    c.onBinderReceived();
    assertTrue("1 retry after received", c.shouldAttemptDirectInject());
    assertTrue("1 generation bumped", c.generation() > before);
    assertEq("1 direct unknown", ShizukuCapability.DirectInject.UNKNOWN, c.directInject());
  }

  /** 2. Binder death fails fast; reconnect allows a new attempt. */
  private static void testBinderDeathFailsFastThenReconnectRetries() {
    ShizukuCapability c = new ShizukuCapability();
    c.onBinderReceived();
    c.onDirectReady();
    assertTrue("2 ready", c.shouldAttemptDirectInject());
    c.onBinderDead();
    assertFalse("2 dead no inject", c.shouldAttemptDirectInject());
    assertEq("2 health dead", ShizukuCapability.BinderHealth.DEAD, c.binderHealth());
    c.onBinderReceived();
    assertTrue("2 retry after reconnect", c.shouldAttemptDirectInject());
    assertEq("2 health ready", ShizukuCapability.BinderHealth.READY, c.binderHealth());
  }

  /** 3. attach() while Shizuku is pingable clears a stale latch. */
  private static void testAttachClearsStaleUnavailable() {
    ShizukuCapability c = new ShizukuCapability();
    c.onDirectUnavailable();
    assertFalse("3 latched", c.shouldAttemptDirectInject());
    c.onAttachWhileReady();
    assertTrue("3 attach recovers", c.shouldAttemptDirectInject());
  }

  /** 4. In-flight work from an old generation is stale after death. */
  private static void testStaleGenerationAbortsInFlight() {
    ShizukuCapability c = new ShizukuCapability();
    int started = c.generation();
    assertFalse("4 fresh not stale", c.isStale(started));
    c.onBinderDead();
    assertTrue("4 stale after death", c.isStale(started));
    assertTrue("4 abort in-flight", c.shouldAbortInFlight(started));
  }

  /** 5. Failed inject + reconnect does not stay disabled. */
  private static void testFailedInjectDoesNotDisableAfterReconnect() {
    ShizukuCapability c = new ShizukuCapability();
    c.onBinderReceived();
    c.onDirectUnavailable();
    assertFalse("5 unavailable", c.shouldAttemptDirectInject());
    c.onDeadObject();
    assertFalse("5 dead", c.shouldAttemptDirectInject());
    c.onBinderReceived();
    assertTrue("5 reconnect retry", c.shouldAttemptDirectInject());
    c.onDirectReady();
    assertTrue("5 ready again", c.shouldAttemptDirectInject());
  }

  /** 6. A blocked/dead generation must not disable the next attempt. */
  private static void testBlockedGenerationDoesNotDisableLaterAttempts() {
    ShizukuCapability c = new ShizukuCapability();
    int first = c.generation();
    c.onBinderDead();
    assertTrue("6 first aborted", c.shouldAbortInFlight(first));
    c.onAttachWhileReady();
    int second = c.generation();
    assertTrue("6 new generation", second != first);
    assertFalse("6 later work not stale", c.isStale(second));
    assertFalse("6 later not aborted", c.shouldAbortInFlight(second));
    assertTrue("6 later may inject", c.shouldAttemptDirectInject());
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
