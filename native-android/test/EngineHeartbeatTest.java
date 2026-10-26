package com.ridio.app;

/**
 * JVM tests for :engine heartbeat freshness.
 * Run: javac -d <out> src/EngineHeartbeat.java test/EngineHeartbeatTest.java
 *      java -cp <out> com.ridio.app.EngineHeartbeatTest
 */
public final class EngineHeartbeatTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    testMissingHeartbeatIsStale();
    testFreshWithinWindow();
    testStaleAfterWindow();
    testClockGoingBackwardsIsStale();
    testBoundaryJustInside();
    testBoundaryAtStaleMs();

    System.out.println();
    System.out.println("EngineHeartbeatTest passed=" + passed + " failed=" + failed);
    if (failed > 0) System.exit(1);
  }

  private static void testMissingHeartbeatIsStale() {
    assertFalse("missing 0", EngineHeartbeat.isFresh(10_000L, 0L));
    assertFalse("missing negative", EngineHeartbeat.isFresh(10_000L, -1L));
  }

  private static void testFreshWithinWindow() {
    assertTrue("same instant", EngineHeartbeat.isFresh(10_000L, 10_000L));
    assertTrue("1s old", EngineHeartbeat.isFresh(11_000L, 10_000L));
    assertTrue("3999ms old", EngineHeartbeat.isFresh(13_999L, 10_000L));
  }

  private static void testStaleAfterWindow() {
    assertFalse("4000ms old", EngineHeartbeat.isFresh(14_000L, 10_000L));
    assertFalse("10s old", EngineHeartbeat.isFresh(20_000L, 10_000L));
  }

  private static void testClockGoingBackwardsIsStale() {
    assertFalse("heartbeat in the future", EngineHeartbeat.isFresh(9_000L, 10_000L));
  }

  private static void testBoundaryJustInside() {
    assertTrue("STALE_MS - 1", EngineHeartbeat.isFresh(
        10_000L + EngineHeartbeat.STALE_MS - 1L, 10_000L));
  }

  private static void testBoundaryAtStaleMs() {
    assertFalse("exactly STALE_MS", EngineHeartbeat.isFresh(
        10_000L + EngineHeartbeat.STALE_MS, 10_000L));
  }

  private static void assertTrue(String name, boolean v) {
    if (v) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected true");
    }
  }

  private static void assertFalse(String name, boolean v) {
    if (!v) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected false");
    }
  }
}
