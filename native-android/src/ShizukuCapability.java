package com.ridio.app;

/**
 * F2 Shizuku / direct-injection capability. Pure JVM logic.
 *
 * <p>{@code injectUnavailable} must not stay latched across binder death,
 * reconnect, or service attach. A failed inject is a transport result, not a
 * confirmed ride and not a permanent disable.
 */
public final class ShizukuCapability {

  public enum BinderHealth {
    UNKNOWN,
    READY,
    DEAD
  }

  public enum DirectInject {
    UNKNOWN,
    READY,
    /** Setup/reflection failed for the current generation only. */
    UNAVAILABLE,
    BINDER_DEAD
  }

  private int generation = 1;
  private BinderHealth binder = BinderHealth.UNKNOWN;
  private DirectInject direct = DirectInject.UNKNOWN;

  public synchronized int generation() {
    return generation;
  }

  public synchronized BinderHealth binderHealth() {
    return binder;
  }

  public synchronized DirectInject directInject() {
    return direct;
  }

  public synchronized boolean isStale(int startedGeneration) {
    return startedGeneration != generation;
  }

  /**
   * Direct inject may be attempted unless this generation already marked it
   * unavailable or the binder is known dead.
   */
  public synchronized boolean shouldAttemptDirectInject() {
    if (binder == BinderHealth.DEAD) return false;
    if (direct == DirectInject.UNAVAILABLE) return false;
    if (direct == DirectInject.BINDER_DEAD) return false;
    return true;
  }

  /** Binder is back. Drop stale proxy state and allow a new bind. */
  public synchronized int onBinderReceived() {
    binder = BinderHealth.READY;
    direct = DirectInject.UNKNOWN;
    generation++;
    return generation;
  }

  /** Binder died. Fail fast; later reconnect may retry. */
  public synchronized int onBinderDead() {
    binder = BinderHealth.DEAD;
    direct = DirectInject.BINDER_DEAD;
    generation++;
    return generation;
  }

  /**
   * Service / engine attach while Shizuku is pingable. Clears a stale
   * unavailable latch from a previous generation.
   */
  public synchronized int onAttachWhileReady() {
    if (binder == BinderHealth.DEAD
        || direct == DirectInject.BINDER_DEAD
        || direct == DirectInject.UNAVAILABLE) {
      binder = BinderHealth.READY;
      direct = DirectInject.UNKNOWN;
      generation++;
      return generation;
    }
    binder = BinderHealth.READY;
    return generation;
  }

  public synchronized void onDirectReady() {
    if (binder == BinderHealth.DEAD) return;
    direct = DirectInject.READY;
  }

  /** Reflection or setup failed. Retry only after binder/attach reset. */
  public synchronized void onDirectUnavailable() {
    if (direct == DirectInject.BINDER_DEAD || binder == BinderHealth.DEAD) {
      return;
    }
    direct = DirectInject.UNAVAILABLE;
  }

  public synchronized int onDeadObject() {
    return onBinderDead();
  }

  /** In-flight work should abort instead of occupying the inject thread. */
  public synchronized boolean shouldAbortInFlight(int startedGeneration) {
    if (isStale(startedGeneration)) return true;
    return binder == BinderHealth.DEAD;
  }
}
