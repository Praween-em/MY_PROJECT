package com.ridio.app;

/**
 * Cross-process :engine liveness. Values are {@code elapsedRealtime} milliseconds.
 * Settings can list the accessibility service while the engine process is dead.
 */
public final class EngineHeartbeat {

  public static final long STALE_MS = 4000L;

  private EngineHeartbeat() {}

  public static boolean isFresh(long nowElapsedMs, long heartbeatElapsedMs) {
    if (heartbeatElapsedMs <= 0L) return false;
    long age = nowElapsedMs - heartbeatElapsedMs;
    return age >= 0L && age < STALE_MS;
  }
}
