package com.ridio.app;

/**
 * JVM tests for F3 attempt vs confirmed-ride separation.
 *
 * Run: javac -d &lt;out&gt; src/AcceptAttemptTracker.java test/AcceptAttemptTrackerTest.java
 *      java -cp &lt;out&gt; com.ridio.app.AcceptAttemptTrackerTest
 */
public final class AcceptAttemptTrackerTest {

  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args) {
    testDispatchOkPendingNotConfirmed();
    testFailedDispatchNotConfirmed();
    testAcceptGoneUncertainNotConfirmed();
    testStrongEvidenceConfirmsOnce();
    testDuplicateEvidenceNoDoubleCount();
    testVerificationTimeoutUncertain();
    testNewAttemptCannotInheritPriorEvidence();
    testConfirmedHistoryIgnoresAttemptAndUncertain();

    System.out.println();
    System.out.println("AcceptAttemptTrackerTest passed=" + passed + " failed=" + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  /** 1. Successful dispatch → pending verification, not confirmed. */
  private static void testDispatchOkPendingNotConfirmed() {
    AcceptAttemptTracker t = new AcceptAttemptTracker();
    long id = t.noteDispatched(1_000L);
    assertTrue("1 attempt id", id > 0L);
    assertEq("1 state pending", AcceptAttemptTracker.State.PENDING_VERIFICATION, t.state());
    assertEq("1 attempts", 1, t.attemptCount());
    assertEq("1 confirmed", 0, t.confirmedCount());
    assertFalse("1 cannot emit confirmed", t.canEmitConfirmed());
    assertFalse("1 consume confirm", t.consumeConfirmedIncrement());
  }

  /** 2. Failed dispatch → not confirmed. */
  private static void testFailedDispatchNotConfirmed() {
    AcceptAttemptTracker t = new AcceptAttemptTracker();
    t.noteDispatchFailed(1_000L);
    assertEq("2 state failed", AcceptAttemptTracker.State.FAILED, t.state());
    assertEq("2 attempts not recorded", 0, t.attemptCount());
    assertEq("2 confirmed", 0, t.confirmedCount());
    t.submit(t.attemptId(), AcceptAttemptTracker.Evidence.PARTNER_ACCEPTED_EXPLICIT, 1_100L);
    assertEq("2 failed stays failed", AcceptAttemptTracker.State.FAILED, t.state());
    assertEq("2 still not confirmed", 0, t.confirmedCount());
  }

  /** 3. Accept control disappears without stronger evidence → uncertain. */
  private static void testAcceptGoneUncertainNotConfirmed() {
    AcceptAttemptTracker t = new AcceptAttemptTracker();
    long id = t.noteDispatched(1_000L);
    t.submit(id, AcceptAttemptTracker.Evidence.ACCEPT_CONTROL_GONE, 1_200L);
    assertEq("3 state uncertain", AcceptAttemptTracker.State.UNCERTAIN, t.state());
    assertEq("3 uncertain count", 1, t.uncertainCount());
    assertEq("3 confirmed", 0, t.confirmedCount());
    assertFalse("3 cannot emit", t.canEmitConfirmed());
  }

  /** 4. Strong, explicitly validated acceptance evidence → confirmed once. */
  private static void testStrongEvidenceConfirmsOnce() {
    AcceptAttemptTracker t = new AcceptAttemptTracker();
    long id = t.noteDispatched(1_000L);
    t.submit(id, AcceptAttemptTracker.Evidence.PARTNER_ACCEPTED_EXPLICIT, 1_200L);
    assertEq("4 state confirmed", AcceptAttemptTracker.State.CONFIRMED, t.state());
    assertEq("4 confirmed", 1, t.confirmedCount());
    assertTrue("4 can emit", t.canEmitConfirmed());
    assertTrue("4 consume once", t.consumeConfirmedIncrement());
    assertFalse("4 consume twice", t.consumeConfirmedIncrement());
    assertEq("4 still one", 1, t.confirmedCount());
  }

  /** 5. Duplicate evidence → no duplicate confirmation/count. */
  private static void testDuplicateEvidenceNoDoubleCount() {
    AcceptAttemptTracker t = new AcceptAttemptTracker();
    long id = t.noteDispatched(1_000L);
    t.submit(id, AcceptAttemptTracker.Evidence.PARTNER_ACCEPTED_EXPLICIT, 1_200L);
    t.submit(id, AcceptAttemptTracker.Evidence.PARTNER_ACCEPTED_EXPLICIT, 1_300L);
    t.submit(id, AcceptAttemptTracker.Evidence.ACCEPT_CONTROL_GONE, 1_400L);
    assertEq("5 confirmed once", 1, t.confirmedCount());
    assertEq("5 stays confirmed", AcceptAttemptTracker.State.CONFIRMED, t.state());
  }

  /** 6. Verification timeout → uncertain. */
  private static void testVerificationTimeoutUncertain() {
    AcceptAttemptTracker t = new AcceptAttemptTracker(2_500L);
    t.noteDispatched(1_000L);
    assertEq("6 still pending before deadline",
        AcceptAttemptTracker.State.PENDING_VERIFICATION, t.evaluateTimeout(3_499L));
    assertEq("6 timeout uncertain",
        AcceptAttemptTracker.State.UNCERTAIN, t.evaluateTimeout(3_500L));
    assertEq("6 confirmed", 0, t.confirmedCount());
    assertEq("6 uncertain", 1, t.uncertainCount());
  }

  /** 7. A new attempt cannot inherit the previous attempt's evidence. */
  private static void testNewAttemptCannotInheritPriorEvidence() {
    AcceptAttemptTracker t = new AcceptAttemptTracker();
    long first = t.noteDispatched(1_000L);
    long second = t.noteDispatched(2_000L);
    assertTrue("7 new id", second != first);
    assertEq("7 first retired uncertain", 1, t.uncertainCount());
    assertEq("7 second pending", AcceptAttemptTracker.State.PENDING_VERIFICATION, t.state());
    t.submit(first, AcceptAttemptTracker.Evidence.PARTNER_ACCEPTED_EXPLICIT, 2_100L);
    assertEq("7 old evidence ignored", 0, t.confirmedCount());
    assertEq("7 still pending", AcceptAttemptTracker.State.PENDING_VERIFICATION, t.state());
    t.beginNewOffer(3_000L);
    t.submit(second, AcceptAttemptTracker.Evidence.PARTNER_ACCEPTED_EXPLICIT, 3_100L);
    assertEq("7 retired id cannot confirm", 0, t.confirmedCount());
    assertEq("7 idle after new offer", AcceptAttemptTracker.State.IDLE, t.state());
  }

  /** 8. Confirmed history is not incremented for attempted or uncertain rides. */
  private static void testConfirmedHistoryIgnoresAttemptAndUncertain() {
    AcceptAttemptTracker t = new AcceptAttemptTracker();
    assertEq("8 start confirmed", 0, t.confirmedCount());
    t.noteDispatched(1_000L);
    t.submit(AcceptAttemptTracker.Evidence.DISPATCH_OK, 1_050L);
    t.submit(AcceptAttemptTracker.Evidence.ON_TRIP_CHROME, 1_100L);
    t.submit(AcceptAttemptTracker.Evidence.JUST_ACCEPTED_LABEL, 1_150L);
    assertEq("8 weak evidence pending", AcceptAttemptTracker.State.PENDING_VERIFICATION, t.state());
    assertEq("8 still zero confirmed", 0, t.confirmedCount());
    t.submit(AcceptAttemptTracker.Evidence.ACCEPT_CONTROL_GONE, 1_200L);
    assertEq("8 uncertain", AcceptAttemptTracker.State.UNCERTAIN, t.state());
    assertEq("8 attempts recorded", 1, t.attemptCount());
    assertEq("8 confirmed still zero", 0, t.confirmedCount());
    assertFalse("8 no consume", t.consumeConfirmedIncrement());
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

  private static void assertEq(String name, boolean expected, boolean actual) {
    if (expected == actual) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(String name, int expected, int actual) {
    assertEq(name, (long) expected, (long) actual);
  }

  private static void assertEq(String name, long expected, long actual) {
    if (expected == actual) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }

  private static void assertEq(
      String name,
      AcceptAttemptTracker.State expected,
      AcceptAttemptTracker.State actual) {
    if (expected == actual) {
      passed++;
      System.out.println("PASS " + name);
    } else {
      failed++;
      System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
    }
  }
}
