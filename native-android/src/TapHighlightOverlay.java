package com.ridio.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Debug marker only. Never sit on the Accept hit pixel — ride apps drop
 * taps that land under a TYPE_ACCESSIBILITY_OVERLAY (FLAG_WINDOW_IS_OBSCURED).
 * Window is hard-capped so OEMs cannot expand it to MATCH_PARENT.
 */
public final class TapHighlightOverlay {

  private static final String TAG = "TapHighlight";
  private static final int MARKER_PX = 28;
  private static final int BOX_MAX_PX = 56;
  private static final int DOT_RADIUS_PX = 8;
  /** Draw the marker above the tap so it does not obscure Accept. */
  private static final int OFFSET_ABOVE_PX = 48;

  private static final Handler main = new Handler(Looper.getMainLooper());
  private static WindowManager wm;
  private static View overlay;
  private static WindowManager.LayoutParams lp;
  private static final Runnable hideRunnable = TapHighlightOverlay::hideNow;
  /** Bumped on hide so a queued show cannot cover Accept mid-inject. */
  private static volatile int generation = 0;

  private TapHighlightOverlay() {}

  /** Remove the marker immediately (sync if already on main). */
  public static void hide() {
    hideImmediate();
  }

  public static void hideImmediate() {
    generation++;
    if (Looper.myLooper() == Looper.getMainLooper()) {
      hideNow();
      return;
    }
    final CountDownLatch latch = new CountDownLatch(1);
    main.post(() -> {
      try {
        hideNow();
      } finally {
        latch.countDown();
      }
    });
    try {
      latch.await(120, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Red box at Accept bounds — capped, never full-screen. */
  public static void show(AccessibilityService svc, Rect bounds, long durationMs) {
    if (svc == null) return;
    final Rect r = new Rect();
    if (bounds != null && !bounds.isEmpty()) {
      r.set(bounds);
    } else {
      return;
    }
    r.inset(-4, -4);
    capRect(r, BOX_MAX_PX);
    final long dur = Math.max(200L, durationMs);
    final AccessibilityService service = svc;
    final int gen = generation;
    main.post(() -> {
      if (gen != generation) return;
      showNow(service, r, dur, false);
    });
  }

  public static void showAt(AccessibilityService svc, int cx, int cy, int half, long durationMs) {
    if (svc == null || cx <= 0 || cy <= 0) return;
    int h = Math.min(BOX_MAX_PX / 2, Math.max(12, half));
    Rect r = new Rect(cx - h, cy - h, cx + h, cy + h);
    show(svc, r, durationMs);
  }

  /**
   * Tiny filled red point, offset above (cx,cy) so the Accept pixel stays clear.
   */
  public static void showPoint(AccessibilityService svc, int cx, int cy, int radius, long durationMs) {
    if (svc == null || cx <= 0 || cy <= 0) return;
    int markerY = Math.max(DOT_RADIUS_PX + 4, cy - OFFSET_ABOVE_PX);
    int half = MARKER_PX / 2;
    Rect bounds = new Rect(cx - half, markerY - half, cx + half, markerY + half);
    final long dur = Math.max(180L, Math.min(durationMs, 900L));
    final AccessibilityService service = svc;
    final int gen = generation;
    main.post(() -> {
      if (gen != generation) return;
      showNow(service, bounds, dur, true);
    });
  }

  private static void capRect(Rect r, int max) {
    if (r.width() > max) {
      int c = r.centerX();
      r.left = c - max / 2;
      r.right = c + max / 2;
    }
    if (r.height() > max) {
      int c = r.centerY();
      r.top = c - max / 2;
      r.bottom = c + max / 2;
    }
  }

  private static void showNow(AccessibilityService svc, Rect r, long durationMs, boolean point) {
    try {
      hideNow();
      if (svc == null) return;
      wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
      if (wm == null) return;

      overlay = point ? new PointView(svc) : new BoxView(svc);

      int type = Build.VERSION.SDK_INT >= 22
          ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
          : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

      int w = point ? MARKER_PX : Math.min(BOX_MAX_PX, Math.max(24, r.width()));
      int h = point ? MARKER_PX : Math.min(BOX_MAX_PX, Math.max(24, r.height()));

      lp = new WindowManager.LayoutParams(
          w,
          h,
          type,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
              | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT
      );
      lp.gravity = Gravity.TOP | Gravity.START;
      lp.x = Math.max(0, r.left);
      lp.y = Math.max(0, r.top);
      if (Build.VERSION.SDK_INT >= 28) {
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      }
      lp.token = null;

      wm.addView(overlay, lp);
      Log.i(TAG, (point ? "POINT" : "SHOW") + " xy=" + lp.x + "," + lp.y
          + " wh=" + lp.width + "x" + lp.height
          + " dur=" + durationMs);
      main.removeCallbacks(hideRunnable);
      main.postDelayed(hideRunnable, durationMs);
    } catch (Throwable t) {
      Log.w(TAG, "show fail: " + t.getMessage());
      hideNow();
    }
  }

  private static void hideNow() {
    generation++;
    main.removeCallbacks(hideRunnable);
    try {
      if (wm != null && overlay != null) {
        wm.removeView(overlay);
      }
    } catch (Throwable ignored) {
    }
    overlay = null;
    lp = null;
  }

  private static final class BoxView extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    BoxView(Context context) {
      super(context);
      stroke.setStyle(Paint.Style.STROKE);
      stroke.setStrokeWidth(3f);
      stroke.setColor(Color.RED);
      fill.setStyle(Paint.Style.FILL);
      fill.setColor(0x33FF0000);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int w = Math.min(getWidth(), BOX_MAX_PX);
      int h = Math.min(getHeight(), BOX_MAX_PX);
      canvas.drawRect(2, 2, w - 2, h - 2, fill);
      canvas.drawRect(2, 2, w - 2, h - 2, stroke);
    }
  }

  private static final class PointView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    PointView(Context context) {
      super(context);
      fill.setStyle(Paint.Style.FILL);
      fill.setColor(Color.RED);
      ring.setStyle(Paint.Style.STROKE);
      ring.setStrokeWidth(2f);
      ring.setColor(Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      // Fixed tiny radius — never scale to view size (OEM MATCH_PARENT was filling the screen)
      float cx = Math.min(getWidth(), MARKER_PX) / 2f;
      float cy = Math.min(getHeight(), MARKER_PX) / 2f;
      canvas.drawCircle(cx, cy, DOT_RADIUS_PX, fill);
      canvas.drawCircle(cx, cy, DOT_RADIUS_PX, ring);
    }
  }
}
