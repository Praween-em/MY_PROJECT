package com.ridio.app;

/**
 * F3 acceptance outcome machine. Attempt history is independent of confirmed
 * rides. Pure JVM logic — no Android APIs.
 *
 * <p>CONFIRMED is allowed only for {@link Evidence#PARTNER_ACCEPTED_EXPLICIT}.
 * This repository does not currently produce that evidence. Dispatch success,
 * Accept-control disappearance, on-trip chrome, and "Accepted" labels are
 * insufficient.
 */
public final class AcceptAttemptTracker {

  public enum State {
    IDLE,
    ATTEMPTED,
    PENDING_VERIFICATION,
    CONFIRMED,
    FAILED,
    UNCERTAIN
  }

  public enum Evidence {
    /** Input / click / inject / notification action was sent. Not confirmation. */
    DISPATCH_OK,
    /** Send / click / inject failed. Not confirmation. */
    DISPATCH_FAIL,
    /** Accept control left the tree. Offer may have expired or been dismissed. */
    ACCEPT_CONTROL_GONE,
    /** Nav / End Ride chrome. Can pre-exist an along-route offer. */
    ON_TRIP_CHROME,
    /** Generic "Accepted" text. Can appear while another Accept is visible. */
    JUST_ACCEPTED_LABEL,
    /** Missed / taken / expired offer UI. */
    MISSED_ORDER,
    /** Verification window ended with no trustworthy outcome. */
    VERIFY_TIMEOUT,
    /** A newer offer started; prior attempt must not inherit later evidence. */
    NEW_OFFER,
    /**
     * Partner-explicit accepted/assigned state for this attempt. Reserved —
     * current Rapido/Ola integrations do not emit a trustworthy instance.
     */
    PARTNER_ACCEPTED_EXPLICIT
  }

  /** Default verification window for {@link #evaluateTimeout(long)}. */
  public static final long DEFAULT_VERIFY_WINDOW_MS = 2500L;

  private final long verifyWindowMs;
  private long nextAttemptId = 1L;
  private long attemptId = 0L;
  private State state = State.IDLE;
  private long verifyDeadlineMs = 0L;
  private long confirmedAttemptId = 0L;
  private int attemptCount = 0;
  private int confirmedCount = 0;
  private int uncertainCount = 0;
  private int failedCount = 0;
  private boolean confirmConsumed = false;

  public AcceptAttemptTracker() {
    this(DEFAULT_VERIFY_WINDOW_MS);
  }

  public AcceptAttemptTracker(long verifyWindowMs) {
    this.verifyWindowMs = verifyWindowMs > 0L ? verifyWindowMs : DEFAULT_VERIFY_WINDOW_MS;
  }

  public synchronized State state() {
    return state;
  }

  public synchronized long attemptId() {
    return attemptId;
  }

  public synchronized int attemptCount() {
    return attemptCount;
  }

  public synchronized int confirmedCount() {
    return confirmedCount;
  }

  public synchronized int uncertainCount() {
    return uncertainCount;
  }

  public synchronized int failedCount() {
    return failedCount;
  }

  /**
   * Record a dispatched action. Moves to {@link State#PENDING_VERIFICATION}.
   * An unresolved prior attempt is marked {@link State#UNCERTAIN} first.
   */
  public synchronized long noteDispatched(long nowMs) {
    retireOpenAttempt(State.UNCERTAIN);
    attemptId = nextAttemptId++;
    attemptCount++;
    state = State.PENDING_VERIFICATION;
    verifyDeadlineMs = nowMs + verifyWindowMs;
    confirmConsumed = false;
    return attemptId;
  }

  /**
   * Dispatch was attempted and failed. Not an attempt-history increment
   * (nothing was actually sent) and never a confirmed ride.
   */
  public synchronized void noteDispatchFailed(long nowMs) {
    retireOpenAttempt(State.UNCERTAIN);
    attemptId = nextAttemptId++;
    state = State.FAILED;
    failedCount++;
    verifyDeadlineMs = 0L;
    confirmConsumed = false;
  }

  /** Evidence applies only to {@code forAttemptId}. Foreign ids are ignored. */
  public synchronized State submit(long forAttemptId, Evidence evidence, long nowMs) {
    if (evidence == null) return state;
    if (forAttemptId <= 0L || forAttemptId != attemptId) return state;
    if (evidence == Evidence.PARTNER_ACCEPTED_EXPLICIT) {
      return confirmIfAllowed();
    }
    if (isHardTerminal()) return state;
    switch (evidence) {
      case DISPATCH_FAIL:
      case MISSED_ORDER:
        markFailed();
        break;
      case ACCEPT_CONTROL_GONE:
      case VERIFY_TIMEOUT:
      case NEW_OFFER:
        markUncertain();
        break;
      case DISPATCH_OK:
      case ON_TRIP_CHROME:
      case JUST_ACCEPTED_LABEL:
        if (state == State.ATTEMPTED) {
          state = State.PENDING_VERIFICATION;
          if (verifyDeadlineMs <= 0L) verifyDeadlineMs = nowMs + verifyWindowMs;
        }
        break;
      default:
        break;
    }
    return state;
  }

  public synchronized State submit(Evidence evidence, long nowMs) {
    return submit(attemptId, evidence, nowMs);
  }

  /** If still pending past the deadline, mark {@link State#UNCERTAIN}. */
  public synchronized State evaluateTimeout(long nowMs) {
    if (state == State.PENDING_VERIFICATION || state == State.ATTEMPTED) {
      if (verifyDeadlineMs > 0L && nowMs >= verifyDeadlineMs) {
        return submit(attemptId, Evidence.VERIFY_TIMEOUT, nowMs);
      }
    }
    return state;
  }

  /**
   * New offer generation. An unresolved attempt becomes {@link State#UNCERTAIN}
   * and later evidence must use the new id.
   */
  public synchronized void beginNewOffer(long nowMs) {
    retireOpenAttempt(State.UNCERTAIN);
    attemptId = 0L;
    state = State.IDLE;
    verifyDeadlineMs = 0L;
    confirmConsumed = false;
  }

  /** True only for the first confirm consume of the current confirmed attempt. */
  public synchronized boolean consumeConfirmedIncrement() {
    if (state != State.CONFIRMED || confirmConsumed) return false;
    if (confirmedAttemptId != attemptId) return false;
    confirmConsumed = true;
    return true;
  }

  public synchronized boolean canEmitConfirmed() {
    return state == State.CONFIRMED && !confirmConsumed && confirmedAttemptId == attemptId;
  }

  private State confirmIfAllowed() {
    if (state == State.FAILED && attemptCount == 0) return state;
    if (state == State.FAILED) return state;
    if (state == State.IDLE) return state;
    if (state == State.CONFIRMED) return state;
    state = State.CONFIRMED;
    if (confirmedAttemptId != attemptId) {
      confirmedAttemptId = attemptId;
      confirmedCount++;
      confirmConsumed = false;
    }
    verifyDeadlineMs = 0L;
    return state;
  }

  private boolean isHardTerminal() {
    return state == State.CONFIRMED || state == State.FAILED;
  }

  private void retireOpenAttempt(State nextIfOpen) {
    if (state == State.ATTEMPTED || state == State.PENDING_VERIFICATION) {
      if (nextIfOpen == State.UNCERTAIN) {
        markUncertain();
      } else if (nextIfOpen == State.FAILED) {
        markFailed();
      }
    }
  }

  private void markUncertain() {
    if (state == State.UNCERTAIN) return;
    if (state == State.CONFIRMED || state == State.FAILED) return;
    state = State.UNCERTAIN;
    uncertainCount++;
    verifyDeadlineMs = 0L;
  }

  private void markFailed() {
    if (state == State.FAILED) return;
    if (state == State.CONFIRMED) return;
    state = State.FAILED;
    failedCount++;
    verifyDeadlineMs = 0L;
  }
}
