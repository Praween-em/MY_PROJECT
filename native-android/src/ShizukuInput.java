package com.ridio.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.ShizukuProvider;
import rikka.shizuku.SystemServiceHelper;

/**
 * Privileged tap / swipe via the Shizuku app (Play Store: moe.shizuku.privileged.api).
 * Uses IInputManager.injectInputEvent as shell UID; falls back to {@code input swipe}
 * (a duration-bearing press). {@code input tap} is too short for Ola.
 */
public final class ShizukuInput {

  private static final String TAG = "ShizukuInput";
  public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
  public static final String PLAY_STORE_URL =
      "https://play.google.com/store/apps/details?id=" + SHIZUKU_PACKAGE;

  /** Do not use WAIT_FOR_FINISH (2) — Ola often never "finish" and DOWN is dropped. */
  private static final int INJECT_ASYNC = 0;

  private static final Executor EXEC = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "shizuku-inject");
    t.setPriority(Thread.MAX_PRIORITY);
    t.setDaemon(true);
    return t;
  });

  private static volatile boolean hiddenExempted = false;
  private static volatile Method injectMethod;
  private static volatile Object inputManagerProxy;
  private static volatile boolean injectUnavailable;
  private static volatile int touchDeviceId = -1;
  /** Bump to cancel an in-flight Rapido coord spray. */
  private static final AtomicInteger sprayEpoch = new AtomicInteger();

  private ShizukuInput() {}

  /** Attach Shizuku binder in this process (needed for {@code :engine}). */
  public static void attach(Context context) {
    if (context == null) return;
    try {
      if (Build.VERSION.SDK_INT >= 28) {
        exemptHiddenApis();
      }
      ShizukuProvider.requestBinderForNonProviderProcess(context.getApplicationContext());
    } catch (Throwable t) {
      Log.w(TAG, "attach: " + t.getMessage());
    }
  }

  public static boolean isInstalled(Context context) {
    if (context == null) return false;
    try {
      context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  public static boolean isRunning() {
    try {
      return Shizuku.pingBinder();
    } catch (Throwable ignored) {
      return false;
    }
  }

  public static boolean hasPermission() {
    try {
      if (!isRunning()) return false;
      return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Throwable ignored) {
      return false;
    }
  }

  public static boolean isReady() {
    return isRunning() && hasPermission();
  }

  public static String state(Context context) {
    if (!isInstalled(context)) return "missing";
    if (!isRunning()) return "stopped";
    if (!hasPermission()) return "denied";
    return "ready";
  }

  public static void execute(Runnable r) {
    if (r == null) return;
    EXEC.execute(r);
  }

  public static boolean tap(int x, int y, long durationMs) {
    if (x <= 0 || y <= 0 || !isReady()) return false;
    final long dur = Math.max(40L, Math.min(durationMs, 180L));
    return runOnInjectThread(() -> {
      if (injectTap(x, y, dur)) return true;
      return shellPress(x, y, dur);
    });
  }

  /**
   * Rapido race: fire-and-forget short press. No latch, no shell, no 40ms hold.
   * Safe from the NLS binder thread.
   */
  public static void tapAsyncFast(int x, int y) {
    if (x <= 0 || y <= 0 || !isReady()) return;
    EXEC.execute(() -> {
      try {
        if (inputManager() == null) return;
        injectTapFast(x, y, 1L);
      } catch (Throwable t) {
        Log.w(TAG, "tapAsyncFast: " + t.getMessage());
      }
    });
  }

  /**
   * Keep injecting 1ms presses at a pixel until {@link #stopCoordSpray} or window ends.
   * Parallel to Accessibility gestures — this is how other tappers win the NLS race.
   */
  public static void startCoordSpray(int x, int y, long windowMs) {
    if (x <= 0 || y <= 0 || !isReady()) return;
    final int epoch = sprayEpoch.incrementAndGet();
    final long until = SystemClock.uptimeMillis() + Math.max(80L, windowMs);
    EXEC.execute(() -> {
      try {
        while (sprayEpoch.get() == epoch && SystemClock.uptimeMillis() < until) {
          if (inputManager() == null) return;
          injectTapFast(x, y, 1L);
        }
      } catch (Throwable t) {
        Log.w(TAG, "coordSpray: " + t.getMessage());
      }
    });
  }

  public static void stopCoordSpray() {
    sprayEpoch.incrementAndGet();
  }

  /**
   * Ola: inject then always shell-press. Inject can report success
   * without the driver app seeing the event.
   */
  public static boolean tapConfirmed(int x, int y, long durationMs) {
    if (x <= 0 || y <= 0 || !isReady()) return false;
    final long dur = Math.max(40L, Math.min(durationMs, 180L));
    return runOnInjectThread(() -> {
      boolean inj = injectTap(x, y, dur);
      boolean sh = shellPress(x, y, dur);
      Log.i(TAG, "tapConfirmed @" + x + "," + y + " inject=" + inj + " shell=" + sh);
      return inj || sh;
    });
  }

  public static boolean hold(int x, int y, long holdMs) {
    if (x <= 0 || y <= 0 || !isReady()) return false;
    final long dur = Math.max(80L, Math.min(holdMs, 6000L));
    return runOnInjectThread(() -> {
      if (injectTap(x, y, dur)) return true;
      return shellPress(x, y, dur);
    });
  }

  public static boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
    if (x1 <= 0 || y1 <= 0 || x2 <= 0 || y2 <= 0 || !isReady()) return false;
    final long dur = Math.max(80L, durationMs);
    return runOnInjectThread(() -> {
      if (injectSwipe(x1, y1, x2, y2, dur)) return true;
      return shellSwipe(x1, y1, x2, y2, dur);
    });
  }

  private interface InjectWork {
    boolean run();
  }

  private static boolean runOnInjectThread(InjectWork work) {
    if (Thread.currentThread().getName().equals("shizuku-inject")) {
      try {
        return work.run();
      } catch (Throwable t) {
        Log.w(TAG, "inject work: " + t.getMessage());
        return false;
      }
    }
    final boolean[] out = { false };
    final CountDownLatch latch = new CountDownLatch(1);
    EXEC.execute(() -> {
      try {
        out[0] = work.run();
      } catch (Throwable t) {
        Log.w(TAG, "inject work: " + t.getMessage());
      } finally {
        latch.countDown();
      }
    });
    try {
      if (!latch.await(8, TimeUnit.SECONDS)) return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    return out[0];
  }

  private static boolean injectTapFast(int x, int y, long durationMs) {
    Object im = inputManager();
    Method inject = injectMethod;
    if (im == null || inject == null) return false;
    long downTime = SystemClock.uptimeMillis();
    long dur = Math.max(1L, Math.min(durationMs, 16L));
    MotionEvent down = obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
    MotionEvent up = null;
    try {
      boolean downOk = invokeInject(im, inject, down);
      try {
        Thread.sleep(dur);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      up = obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
      return downOk || invokeInject(im, inject, up);
    } catch (Throwable t) {
      return false;
    } finally {
      try { down.recycle(); } catch (Exception ignored) {}
      if (up != null) try { up.recycle(); } catch (Exception ignored) {}
    }
  }

  private static boolean injectTap(int x, int y, long durationMs) {
    Object im = inputManager();
    Method inject = injectMethod;
    if (im == null || inject == null) return false;
    long downTime = SystemClock.uptimeMillis();
    long dur = Math.max(40L, durationMs);
    MotionEvent down = obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
    MotionEvent move = null;
    MotionEvent up = null;
    try {
      boolean downOk = invokeInject(im, inject, down);
      try {
        Thread.sleep(Math.min(dur, 6000L));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      // Same-point MOVE — Compose often ignores DOWN/UP with no move; +1px can cancel
      move = obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y);
      invokeInject(im, inject, move);
      up = obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
      boolean upOk = invokeInject(im, inject, up);
      boolean ok = downOk || upOk;
      Log.i(TAG, "injectTap @" + x + "," + y + " dur=" + dur + " down=" + downOk + " up=" + upOk);
      return ok;
    } catch (Throwable t) {
      Log.w(TAG, "injectTap fail: " + t.getMessage());
      return false;
    } finally {
      try { down.recycle(); } catch (Exception ignored) {}
      if (move != null) try { move.recycle(); } catch (Exception ignored) {}
      if (up != null) try { up.recycle(); } catch (Exception ignored) {}
    }
  }

  private static boolean injectSwipe(int x1, int y1, int x2, int y2, long durationMs) {
    Object im = inputManager();
    Method inject = injectMethod;
    if (im == null || inject == null) return false;
    int steps = Math.max(6, (int) (durationMs / 12L));
    steps = Math.min(steps, 24);
    long downTime = SystemClock.uptimeMillis();
    MotionEvent down = obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1);
    try {
      invokeInject(im, inject, down);
      for (int i = 1; i <= steps; i++) {
        float t = i / (float) steps;
        int x = Math.round(x1 + (x2 - x1) * t);
        int y = Math.round(y1 + (y2 - y1) * t);
        long et = downTime + (durationMs * i / steps);
        MotionEvent move = obtain(downTime, et, MotionEvent.ACTION_MOVE, x, y);
        try {
          invokeInject(im, inject, move);
        } finally {
          try { move.recycle(); } catch (Exception ignored) {}
        }
      }
      MotionEvent up = obtain(downTime, downTime + durationMs, MotionEvent.ACTION_UP, x2, y2);
      try {
        return invokeInject(im, inject, up);
      } finally {
        try { up.recycle(); } catch (Exception ignored) {}
      }
    } catch (Throwable t) {
      Log.w(TAG, "injectSwipe fail: " + t.getMessage());
      return false;
    } finally {
      try { down.recycle(); } catch (Exception ignored) {}
    }
  }

  private static boolean invokeInject(Object im, Method inject, MotionEvent event) throws Exception {
    Object r = inject.invoke(im, event, INJECT_ASYNC);
    return r instanceof Boolean ? (Boolean) r : true;
  }

  private static MotionEvent obtain(long downTime, long eventTime, int action, int x, int y) {
    MotionEvent.PointerProperties[] pps = new MotionEvent.PointerProperties[1];
    pps[0] = new MotionEvent.PointerProperties();
    pps[0].id = 0;
    pps[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords[] pcs = new MotionEvent.PointerCoords[1];
    pcs[0] = new MotionEvent.PointerCoords();
    pcs[0].x = x;
    pcs[0].y = y;
    pcs[0].pressure = 1f;
    pcs[0].size = 0.2f;
    MotionEvent e = MotionEvent.obtain(
        downTime, eventTime, action, 1, pps, pcs,
        0, 0, 1f, 1f, touchDeviceId(), 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    applyDisplayId(e, 0);
    return e;
  }

  /** API 30+ MotionEvent.setDisplayId — compile-safe via reflection. */
  private static void applyDisplayId(MotionEvent e, int displayId) {
    if (e == null || Build.VERSION.SDK_INT < 30) return;
    try {
      Method m = MotionEvent.class.getMethod("setDisplayId", int.class);
      m.invoke(e, displayId);
    } catch (Throwable ignored) {
    }
  }

  private static int touchDeviceId() {
    if (touchDeviceId >= 0) return touchDeviceId;
    try {
      int[] ids = InputDevice.getDeviceIds();
      if (ids != null) {
        for (int id : ids) {
          InputDevice d = InputDevice.getDevice(id);
          if (d != null && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) != 0) {
            touchDeviceId = id;
            return id;
          }
        }
      }
    } catch (Throwable ignored) {
    }
    touchDeviceId = 0;
    return 0;
  }

  private static Object inputManager() {
    if (injectUnavailable) return null;
    if (inputManagerProxy != null && injectMethod != null) return inputManagerProxy;
    synchronized (ShizukuInput.class) {
      if (injectUnavailable) return null;
      if (inputManagerProxy != null && injectMethod != null) return inputManagerProxy;
      try {
        exemptHiddenApis();
        IBinder raw = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE);
        if (raw == null) {
          injectUnavailable = true;
          return null;
        }
        IBinder wrapped = new ShizukuBinderWrapper(raw);
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object proxy = asInterface.invoke(null, wrapped);
        if (proxy == null) {
          injectUnavailable = true;
          return null;
        }
        Method inject = proxy.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);
        inputManagerProxy = proxy;
        injectMethod = inject;
        Log.i(TAG, "IInputManager ready");
        return proxy;
      } catch (Throwable t) {
        Log.w(TAG, "IInputManager unavailable: " + t.getMessage());
        injectUnavailable = true;
        return null;
      }
    }
  }

  private static void exemptHiddenApis() {
    if (hiddenExempted || Build.VERSION.SDK_INT < 28) {
      hiddenExempted = true;
      return;
    }
    try {
      Class<?> c = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
      Method m = c.getDeclaredMethod("addHiddenApiExemptions", String[].class);
      m.invoke(null, (Object) new String[] { "L" });
      hiddenExempted = true;
    } catch (Throwable t) {
      hiddenExempted = true;
    }
  }

  /** Duration-bearing press. {@code input tap} is ~1ms and Ola ignores it. */
  private static boolean shellPress(int x, int y, long durationMs) {
    return shellSwipe(x, y, x + 1, y + 1, Math.max(40L, durationMs));
  }

  private static boolean shellSwipe(int x1, int y1, int x2, int y2, long durationMs) {
    return shell(new String[] {
        "input", "swipe",
        String.valueOf(x1), String.valueOf(y1),
        String.valueOf(x2), String.valueOf(y2),
        String.valueOf(durationMs)
    });
  }

  private static volatile Method newProcessMethod;

  private static boolean shell(String[] cmd) {
    java.lang.Process p = null;
    try {
      Method m = newProcessMethod;
      if (m == null) {
        m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        newProcessMethod = m;
      }
      p = (java.lang.Process) m.invoke(null, cmd, null, null);
      if (p == null) return false;
      int code = p.waitFor();
      return code == 0;
    } catch (Throwable t) {
      Log.w(TAG, "shell fail: " + t.getMessage());
      return false;
    } finally {
      if (p != null) {
        try { p.destroy(); } catch (Exception ignored) {}
      }
    }
  }
}
