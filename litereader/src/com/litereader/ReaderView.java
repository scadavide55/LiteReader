package com.litereader;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;

/** GL surface + touch: one-finger drag/fling, pinch zoom with focal point, double-tap zoom, edge taps, centre tap for the menu. */
final class ReaderView extends GLSurfaceView {
    interface Listener { void onCenterTap(); }

    final Engine engine;
    final Listener listener;
    final Handler handler = new Handler();
    final float slop;
    VelocityTracker vt;
    float lastX, lastY, downX, downY, pinchDist, pinchX, pinchY;
    long downTime, lastTapTime; float lastTapX, lastTapY;
    boolean moved, pinching;
    final Runnable centerRun = new Runnable() { public void run() { listener.onCenterTap(); } };

    ReaderView(Context c, Engine e, Listener l) {
        super(c);
        engine = e; listener = l;
        slop = 8f * c.getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(5, 6, 5, 0, 0, 0);
        setRenderer(e);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (act) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX(); downY = lastY = e.getY(); downTime = e.getEventTime(); moved = false; pinching = false;
                engine.touchDown();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) { pinching = true; moved = true; pinchDist = dist(e); pinchX = (e.getX(0) + e.getX(1)) / 2; pinchY = (e.getY(0) + e.getY(1)) / 2; }
                break;
            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float d = dist(e), mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                    if (pinchDist > 0) engine.pinch(d / pinchDist, mx - getWidth() / 2f, my - getHeight() / 2f, mx - pinchX, my - pinchY);
                    pinchDist = d; pinchX = mx; pinchY = my;
                } else if (!pinching) {
                    float x = e.getX(), y = e.getY();
                    if (!moved && Math.abs(x - downX) < slop && Math.abs(y - downY) < slop) break;
                    moved = true;
                    engine.drag(x - lastX, y - lastY);
                    lastX = x; lastY = y;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() == 2) {      // one finger stays: continue as a drag from its position
                    int keep = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(keep); lastY = e.getY(keep); pinching = false;
                    downX = lastX; downY = lastY;
                    vt.clear();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                if (act == MotionEvent.ACTION_UP && !moved && e.getEventTime() - downTime < 350) onTap(e.getX(), e.getY(), e.getEventTime());
                if (pinching || moved || act == MotionEvent.ACTION_CANCEL) engine.touchUp(pinching ? 0 : vx, pinching ? 0 : vy);
                else engine.touchUp(0, 0);
                vt.recycle(); vt = null;
                pinching = false;
                break;
        }
        return true;
    }

    float dist(MotionEvent e) { float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1); return (float) Math.sqrt(dx * dx + dy * dy); }

    void onTap(float x, float y, long t) {
        int w = getWidth();
        if (x < 0.25f * w || x > 0.75f * w) { engine.tap(x); return; }
        if (t - lastTapTime < 300 && Math.abs(x - lastTapX) < 60 * getResources().getDisplayMetrics().density) {
            handler.removeCallbacks(centerRun);
            lastTapTime = 0;
            engine.doubleTap(x, y);
        } else {
            lastTapTime = t; lastTapX = x; lastTapY = y;
            handler.postDelayed(centerRun, 280);
        }
    }

    void cancelPending() { handler.removeCallbacks(centerRun); }
}
