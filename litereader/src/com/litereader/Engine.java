package com.litereader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GL page engine: one texture per page (L8 or RGB565), native decode on 2 worker threads, prefetch window,
 * on-disk 1/6-size thumbnails as instant placeholders, pinch/pan/double-tap zoom and drag/fling page turns
 * all driven on the GL thread so gestures never wait for decoding.
 */
final class Engine implements GLSurfaceView.Renderer {
    static final String TAG = "LiteReader";
    interface Host {
        void onPage(int page);               // GL thread
        void onThumbProgress(int done, int total);
    }

    static final int AHEAD = 3, BEHIND = 1, FULL_CAP = 6 << 20, THUMB_CAP = 150000, SLOT = 151552, MAGIC = 0x4c52544d;
    static final float MAXZ = 5f, DOUBLE_Z = 2.5f, SLIDE_SPEED = 5.5f;   // pages per second for a turn animation
    static final int LEFT_ZONE = 0, CENTER_ZONE = 1, RIGHT_ZONE = 2;

    final Book book;
    final Host host;
    final int n;
    final Object lock = new Object();

    // --- state shared with UI thread (guarded by lock unless volatile) ---
    volatile int target;
    volatile int dir = 1;
    float pos, dragX, zoom = 1f, panX, panY, overX;
    float velX, velY;
    boolean flinging, zoomAnim, dragging;
    long zaStart; float zaFrom, zaTo, zaFx, zaFy, zaPx, zaPy;
    boolean rtl;
    volatile int vw = 1, vh = 1;
    volatile float fitW = 1, fitH = 1;
    final AtomicInteger pendJump = new AtomicInteger(-1), pendTurn = new AtomicInteger(0);
    volatile boolean needSchedule = true;
    volatile long lastActive = SystemClock.uptimeMillis();
    volatile boolean paused = false;

    // --- GL thread only ---
    int prog, aPos, aUV, uScale, uOff;
    FloatBuffer quad;
    static class Tex { int id, w, h; }
    final SparseArray<Tex> tex = new SparseArray<Tex>(), thumbTex = new SparseArray<Tex>();
    int lastNotified = -1;
    long lastFrameNs;

    // --- decode pipeline ---
    static class Decoded { int page, w, h, fmt, rowBytes, off; ByteBuffer buf; boolean thumb; }
    final ConcurrentLinkedQueue<Decoded> done = new ConcurrentLinkedQueue<Decoded>(), doneThumb = new ConcurrentLinkedQueue<Decoded>();
    final ConcurrentLinkedQueue<ByteBuffer> fullPool = new ConcurrentLinkedQueue<ByteBuffer>(), thumbPool = new ConcurrentLinkedQueue<ByteBuffer>();
    final ArrayList<Integer> inflight = new ArrayList<Integer>(), thumbInflight = new ArrayList<Integer>();
    ThreadPoolExecutor pool, thumbPoolEx;
    Decoded up; int upTex, upRow;
    static final int UPLOAD_BUDGET_US = 2500, URGENT_BUDGET_US = 7000;

    // --- thumbnails ---
    final byte[] thumbState;     // 0 unknown, 1 have, 2 absent
    RandomAccessFile thumbRaf; FileChannel thumbCh;
    volatile int builtCount = 0;
    Thread builder;
    volatile boolean stopped = false;
    boolean firstLogged;
    final long created = SystemClock.uptimeMillis();

    final ThreadLocal<Wk> wkTl = new ThreadLocal<Wk>();
    static class Wk { RandomAccessFile raf; FileChannel ch; ByteBuffer src; int srcCap; int[] info = new int[8]; byte[] hdr = new byte[30]; }

    Engine(Book b, Host h, File thumbFile, int startPage, boolean rtl) {
        book = b; host = h; n = b.n;
        target = Math.max(0, Math.min(n - 1, startPage)); pos = target; this.rtl = rtl;
        thumbState = new byte[n];
        try {
            thumbRaf = new RandomAccessFile(thumbFile, "rw");
            thumbCh = thumbRaf.getChannel();
        } catch (Exception e) { thumbCh = null; Log.w(TAG, "thumb file: " + e); }
        ThreadFactory tf = new ThreadFactory() {
            public Thread newThread(final Runnable r) {
                return new Thread(new Runnable() { public void run() { Process.setThreadPriority(5); r.run(); } });
            }
        };
        pool = new ThreadPoolExecutor(2, 2, 1, TimeUnit.MINUTES, new PriorityBlockingQueue<Runnable>(), tf);
        thumbPoolEx = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new PriorityBlockingQueue<Runnable>());
    }

    // =============== API for the UI thread ===============
    int page() { return target; }
    boolean isRtl() { synchronized (lock) { return rtl; } }
    void setRtl(boolean v) { synchronized (lock) { rtl = v; dragX = 0; } }
    void touchActive() { lastActive = SystemClock.uptimeMillis(); }
    void requestTurn(int d) { touchActive(); pendTurn.addAndGet(d); }
    void requestJump(int p) { touchActive(); pendJump.set(Math.max(0, Math.min(n - 1, p))); }

    void touchDown() { synchronized (lock) { dragging = true; flinging = false; zoomAnim = false; } touchActive(); }

    void drag(float dx, float dy) {
        synchronized (lock) {
            touchActive();
            if (zoom <= 1.001f) {
                dragX += dx;
                float m = vw; if (dragX > m) dragX = m; else if (dragX < -m) dragX = -m;
            } else {
                float mx = maxPanX(), my = maxPanY();
                float nx = panX + dx, ny = panY + dy;
                float cx = Math.max(-mx, Math.min(mx, nx)), cy = Math.max(-my, Math.min(my, ny));
                if (cx != nx) overX += nx - cx; else if ((dx > 0) == (overX < 0)) overX = 0;
                panX = cx; panY = cy;
            }
        }
    }

    void pinch(float factor, float fx, float fy, float dfx, float dfy) {
        synchronized (lock) {
            touchActive();
            float z0 = zoom, z1 = Math.max(1f, Math.min(MAXZ, z0 * factor));
            panX = fx - (fx - panX) * (z1 / z0) + dfx;
            panY = fy - (fy - panY) * (z1 / z0) + dfy;
            zoom = z1; dragX = 0; overX = 0;
            clampPan();
            if (zoom <= 1.001f) { zoom = 1f; panX = panY = 0; }
        }
    }

    void touchUp(float vx, float vy) {
        synchronized (lock) {
            dragging = false; touchActive();
            int ds = rtl ? -1 : 1, want = 0;
            if (zoom <= 1.001f) {
                if (Math.abs(vx) > 700 && Math.abs(dragX) > 12) want = vx > 0 ? -ds : ds;
                else if (Math.abs(dragX) > 0.18f * vw) want = dragX > 0 ? -ds : ds;
                if (want != 0 && !turnLocked(want)) want = 0;
                if (want == 0) { pos -= dragX * ds / vw; dragX = 0; }   // snap back through the page animation
            } else {
                if (Math.abs(overX) > 0.12f * vw) want = overX > 0 ? -ds : ds;
                overX = 0;
                if (want != 0 && turnLocked(want)) return;
                velX = vx; velY = vy; flinging = Math.abs(vx) > 60 || Math.abs(vy) > 60;
            }
        }
    }

    void tap(float x) {
        int ds;
        synchronized (lock) { ds = rtl ? -1 : 1; }
        if (x < 0.25f * vw) requestTurn(-ds);
        else if (x > 0.75f * vw) requestTurn(ds);
    }

    void doubleTap(float x, float y) {
        synchronized (lock) {
            touchActive();
            zoomAnim = true; flinging = false; zaStart = SystemClock.uptimeMillis();
            zaFrom = zoom; zaTo = zoom > 1.1f ? 1f : DOUBLE_Z;
            zaFx = x - vw / 2f; zaFy = y - vh / 2f; zaPx = panX; zaPy = panY;
        }
    }

    // =============== locked helpers ===============
    float maxPanX() { return Math.max(0f, (fitW * zoom - vw) / 2f); }
    float maxPanY() { return Math.max(0f, (fitH * zoom - vh) / 2f); }
    void clampPan() {
        float mx = maxPanX(), my = maxPanY();
        panX = Math.max(-mx, Math.min(mx, panX)); panY = Math.max(-my, Math.min(my, panY));
    }

    /** start an animated page turn by `want` pages (+1 reveals the next page). Keeps the visible page continuous. */
    boolean turnLocked(int want) {
        int ds = rtl ? -1 : 1;
        int nt = target + want;
        if (nt < 0 || nt >= n) return false;
        pos -= dragX * ds / vw; dragX = 0;
        zoom = 1f; panX = panY = 0; overX = 0; flinging = false; zoomAnim = false;
        dir = want > 0 ? 1 : -1;
        target = nt; needSchedule = true;
        return true;
    }

    void jumpLocked(int p) {
        dir = p >= target ? 1 : -1;
        target = p; pos = p; dragX = 0; zoom = 1f; panX = panY = 0; overX = 0; flinging = false; zoomAnim = false;
        needSchedule = true;
    }

    // =============== GL ===============
    static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s);
        return s;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        String vs = "attribute vec2 aPos;attribute vec2 aUV;uniform vec2 uScale;uniform vec2 uOff;varying vec2 vUV;"
            + "void main(){gl_Position=vec4(aPos*uScale+uOff,0.0,1.0);vUV=aUV;}";
        String fs = "precision mediump float;varying vec2 vUV;uniform sampler2D uTex;void main(){gl_FragColor=texture2D(uTex,vUV);}";
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(prog);
        aPos = GLES20.glGetAttribLocation(prog, "aPos"); aUV = GLES20.glGetAttribLocation(prog, "aUV");
        uScale = GLES20.glGetUniformLocation(prog, "uScale"); uOff = GLES20.glGetUniformLocation(prog, "uOff");
        float[] q = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);
        tex.clear(); thumbTex.clear();     // context was (re)created: old texture names are gone
        if (up != null) { fullPool.add(up.buf); up = null; }
        needSchedule = true;
        lastNotified = -1;
        startBuilder();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) { vw = Math.max(1, w); vh = Math.max(1, h); GLES20.glViewport(0, 0, w, h); }

    static void texParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    boolean wanted(int p) {
        int t = target, d = dir;
        int lo = d >= 0 ? t - BEHIND : t - AHEAD, hi = d >= 0 ? t + AHEAD : t + BEHIND;
        return p >= lo && p <= hi;
    }
    boolean thumbWanted(int p) { int t = target; return p >= t - 6 && p <= t + 10; }

    void schedule() {
        int t = target, d = dir;
        ArrayList<Integer> want = new ArrayList<Integer>(6);
        want.add(t);
        for (int i = 1; i <= AHEAD; i++) want.add(d >= 0 ? t + i : t - i);
        want.add(d >= 0 ? t - 1 : t + 1);
        for (int k = 0; k < want.size(); k++) {
            int p = want.get(k);
            if (p < 0 || p >= n || tex.get(p) != null) continue;
            synchronized (inflight) { if (inflight.contains(p)) continue; inflight.add(p); }
            pool.execute(new Task(this, p, k, false));
        }
        for (int k = -6; k <= 10; k++) {
            int p = t + k;
            if (p < 0 || p >= n || thumbTex.get(p) != null || tex.get(p) != null || thumbState[p] == 2) continue;
            synchronized (thumbInflight) { if (thumbInflight.contains(p)) continue; thumbInflight.add(p); }
            thumbPoolEx.execute(new Task(this, p, Math.abs(k), true));
        }
        for (int i = tex.size() - 1; i >= 0; i--) {
            int p = tex.keyAt(i);
            if (!wanted(p) && Math.abs(p - target) > 1) { GLES20.glDeleteTextures(1, new int[]{tex.valueAt(i).id}, 0); tex.removeAt(i); }
        }
        for (int i = thumbTex.size() - 1; i >= 0; i--) {
            int p = thumbTex.keyAt(i);
            if (p < t - 12 || p > t + 16) { GLES20.glDeleteTextures(1, new int[]{thumbTex.valueAt(i).id}, 0); thumbTex.removeAt(i); }
        }
    }

    static class Task implements Runnable, Comparable<Task> {
        final Engine e; final int page, prio; final boolean thumb;
        Task(Engine e, int page, int prio, boolean thumb) { this.e = e; this.page = page; this.prio = prio; this.thumb = thumb; }
        public int compareTo(Task o) { return prio - o.prio; }
        public void run() { if (thumb) e.loadThumb(page); else e.decodeFull(page); }
    }

    // =============== workers ===============
    Wk wk() throws Exception {
        Wk w = wkTl.get();
        if (w == null) {
            w = new Wk();
            w.raf = new RandomAccessFile(book.file, "r"); w.ch = w.raf.getChannel();
            wkTl.set(w);
        }
        return w;
    }

    /** reads the compressed bytes of page `p` into w.src; returns length */
    int readEntry(Wk w, int p) throws Exception {
        if (book.raw == null) { w.raf.seek(book.off[p]); w.raf.readFully(w.hdr); }
        long data = book.raw != null ? book.raw[p] : book.off[p] + 30 + Book.le16(w.hdr, 26) + Book.le16(w.hdr, 28);
        int len = book.csz[p];
        if (w.src == null || w.srcCap < len) {
            if (w.src != null) NativeDec.free(w.src);
            w.srcCap = Math.max(len, 1 << 20);
            w.src = NativeDec.alloc(w.srcCap);
        }
        w.src.clear(); w.src.limit(len);
        long pos = data;
        while (w.src.hasRemaining()) { int r = w.ch.read(w.src, pos); if (r < 0) break; pos += r; }
        return len;
    }

    ByteBuffer takeFull() { ByteBuffer b = fullPool.poll(); return b != null ? b : NativeDec.alloc(FULL_CAP); }
    ByteBuffer takeThumb() { ByteBuffer b = thumbPool.poll(); return b != null ? b : NativeDec.alloc(SLOT); }

    void decodeFull(int page) {
        ByteBuffer out = null, th = null;
        try {
            if (!wanted(page) || stopped) return;
            Wk w = wk();
            int len = book.mu != 0 ? 0 : readEntry(w, page);
            out = takeFull();
            boolean needThumb = thumbCh != null && thumbState[page] != 1;
            ByteBuffer ths = null;
            if (needThumb) { th = takeThumb(); th.clear(); th.position(16); ths = th.slice(); }
            int rc = book.mu != 0 ? NativeMu.render(book.mu, page, out, FULL_CAP, ths, THUMB_CAP, w.info) : NativeDec.decode(w.src, len, book.comp[page], out, FULL_CAP, ths, THUMB_CAP, w.info);
            int[] in = w.info;
            if (rc != 0) {
                if (book.mu != 0 || !fallback(w, page, len, out, in)) { Log.w(TAG, "page " + page + " undecodable rc=" + rc); return; }
                in[4] = in[5] = 0;
            }
            Decoded d = new Decoded();
            d.page = page; d.w = in[0]; d.h = in[1]; d.fmt = in[2]; d.rowBytes = in[3]; d.buf = out; d.off = 0;
            if (!wanted(page)) { fullPool.add(out); out = null; } else { done.add(d); out = null; }
            if (th != null && in[4] > 0) { th = finishThumb(page, th, in); }
        } catch (Throwable t) {
            Log.w(TAG, "decodeFull " + page + ": " + t);
        } finally {
            if (out != null) fullPool.add(out);
            if (th != null) thumbPool.add(th);
            synchronized (inflight) { inflight.remove((Integer) page); }
        }
    }

    /** writes the thumbnail slot, queues it for upload if still wanted; returns the buffer to recycle or null */
    ByteBuffer finishThumb(int page, ByteBuffer th, int[] in) {
        try {
            int tw = in[4], tH = in[5], len = tw * tH * (in[2] == 1 ? 2 : 1);
            th.order(ByteOrder.nativeOrder());
            th.clear();
            th.putInt(0, MAGIC); th.putInt(4, tw); th.putInt(8, tH); th.putInt(12, in[2]);
            th.limit(16 + len); th.position(0);
            long pos = (long) page * SLOT;
            while (th.hasRemaining()) pos += thumbCh.write(th, pos);
            thumbState[page] = 1;
            if (thumbWanted(page)) {
                Decoded d = new Decoded();
                d.page = page; d.w = tw; d.h = tH; d.fmt = in[2]; d.buf = th; d.off = 16; d.thumb = true;
                doneThumb.add(d);
                return null;
            }
            return th;
        } catch (Throwable t) { Log.w(TAG, "thumb write: " + t); return th; }
    }

    void loadThumb(int page) {
        ByteBuffer th = null;
        try {
            if (!thumbWanted(page) || stopped || thumbCh == null) return;
            th = takeThumb(); th.clear(); th.order(ByteOrder.nativeOrder());
            long pos = (long) page * SLOT;
            int got = 0;
            while (th.hasRemaining()) { int r = thumbCh.read(th, pos + got); if (r <= 0) break; got += r; }
            if (got < 16 || th.getInt(0) != MAGIC) { thumbState[page] = 2; return; }
            int tw = th.getInt(4), tH = th.getInt(8), fmt = th.getInt(12);
            if (tw <= 0 || tH <= 0 || 16 + tw * tH * (fmt == 1 ? 2 : 1) > got) { thumbState[page] = 2; return; }
            thumbState[page] = 1;
            Decoded d = new Decoded();
            d.page = page; d.w = tw; d.h = tH; d.fmt = fmt; d.buf = th; d.off = 16; d.thumb = true;
            doneThumb.add(d);
            th = null;
        } catch (Throwable t) { Log.w(TAG, "loadThumb: " + t); }
        finally {
            if (th != null) thumbPool.add(th);
            synchronized (thumbInflight) { thumbInflight.remove((Integer) page); }
        }
    }

    /** rare path (unsupported PNG/JPEG variant, other formats): Java decoder into the same buffer. */
    boolean fallback(Wk w, int page, int len, ByteBuffer out, int[] info) {
        try {
            byte[] raw = new byte[len];
            w.src.clear(); w.src.get(raw, 0, len);
            if (book.comp[page] == 8) {
                Inflater inf = new Inflater(true);
                inf.setInput(raw);
                byte[] o = new byte[book.usz[page]];
                int g = 0;
                while (g < o.length) { int r = inf.inflate(o, g, o.length - g); if (r == 0 && (inf.finished() || inf.needsInput())) break; g += r; }
                inf.end(); raw = o;
            }
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bo);
            if (bo.outWidth <= 0) return false;
            int s = 1;
            while ((bo.outWidth / s) * (bo.outHeight / s) * 2 > FULL_CAP || Math.max(bo.outWidth, bo.outHeight) / s > 2048) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inPreferredConfig = Bitmap.Config.RGB_565; o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
            if (bm == null) return false;
            out.clear(); bm.copyPixelsToBuffer(out); out.position(0);
            info[0] = bm.getWidth(); info[1] = bm.getHeight(); info[2] = 1; info[3] = bm.getRowBytes();
            bm.recycle();
            return true;
        } catch (Throwable t) { return false; }
    }

    // ---- background thumbnail builder (low priority, pauses while the user is active) ----
    void startBuilder() {
        if (builder != null && builder.isAlive() || thumbCh == null) return;
        builder = new Thread(new Runnable() { public void run() { buildLoop(); } }, "thumbs");
        builder.start();
    }

    void buildLoop() {
        Process.setThreadPriority(15);
        try {
            Wk w = new Wk();
            w.raf = new RandomAccessFile(book.file, "r"); w.ch = w.raf.getChannel();
            ByteBuffer th = NativeDec.alloc(SLOT);
            ByteBuffer hdr = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            int total = n, doneCount = 0;
            for (int o = 0; o < n && !stopped; o++) {
                while (!stopped && (paused || SystemClock.uptimeMillis() - lastActive < 3000 || pool.getQueue().size() > 0 || !inflight.isEmpty())) Thread.sleep(200);
                if (stopped) break;
                int p = (target + o) % n;
                if (thumbState[p] == 0) {
                    hdr.clear();
                    int r = thumbCh.read(hdr, (long) p * SLOT);
                    if (r == 16 && hdr.getInt(0) == MAGIC) thumbState[p] = 1;
                }
                if (thumbState[p] != 1) {
                    int len = book.mu != 0 ? 0 : readEntry(w, p);
                    th.clear(); th.position(16); ByteBuffer ths = th.slice();
                    int rc = book.mu != 0 ? NativeMu.render(book.mu, p, null, 0, ths, THUMB_CAP, w.info) : NativeDec.decode(w.src, len, book.comp[p], null, 0, ths, THUMB_CAP, w.info);
                    if (rc == 0 && w.info[4] > 0) {
                        th.clear(); th.order(ByteOrder.nativeOrder());
                        int tw = w.info[4], tH = w.info[5], len2 = tw * tH * (w.info[2] == 1 ? 2 : 1);
                        th.putInt(0, MAGIC); th.putInt(4, tw); th.putInt(8, tH); th.putInt(12, w.info[2]);
                        th.limit(16 + len2); th.position(0);
                        long pos = (long) p * SLOT;
                        while (th.hasRemaining()) pos += thumbCh.write(th, pos);
                        thumbState[p] = 1;
                    } else thumbState[p] = 2;
                }
                doneCount++;
                builtCount = doneCount;
                if (doneCount % 25 == 0 || doneCount == total) host.onThumbProgress(doneCount, total);
            }
        } catch (Throwable t) { Log.w(TAG, "builder: " + t); }
    }

    void pause() { paused = true; }
    void resume() { paused = false; touchActive(); }
    void stop() {
        stopped = true; paused = true;
        pool.shutdownNow(); thumbPoolEx.shutdownNow();
        try { if (thumbRaf != null) thumbRaf.close(); } catch (Exception e) { }
    }

    // =============== per-frame ===============
    void uploadThumbs() {
        for (int k = 0; k < 3; k++) {
            Decoded d = doneThumb.poll();
            if (d == null) return;
            if (tex.get(d.page) != null || thumbTex.get(d.page) != null || !thumbWanted(d.page) && Math.abs(d.page - target) > 16) { thumbPool.add(d.buf); continue; }
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            texParams();
            d.buf.position(d.off);
            if (d.fmt == 0) {
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, d.w, d.h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, d.buf);
            } else {
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, d.w, d.h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, d.buf);
            }
            Tex x = new Tex(); x.id = t[0]; x.w = d.w; x.h = d.h;
            thumbTex.put(d.page, x);
            thumbPool.add(d.buf);
        }
    }

    void uploadFull() {
        long start = System.nanoTime();
        while (true) {
            if (up == null) {
                Decoded d = done.poll();
                if (d == null) return;
                if (!wanted(d.page) && Math.abs(d.page - target) > 1) { fullPool.add(d.buf); continue; }
                up = d; upRow = 0;
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                upTex = t[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upTex);
                texParams();
                if (d.fmt == 0) {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, d.w, d.h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
                } else {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, d.w, d.h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, null);
                }
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upTex);
            int rows = Math.min(Math.max(16, 120000 / Math.max(1, up.rowBytes)), up.h - upRow);
            up.buf.position(upRow * up.rowBytes);
            if (up.fmt == 0) GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, upRow, up.w, rows, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, up.buf);
            else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, upRow, up.w, rows, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, up.buf);
            upRow += rows;
            if (upRow >= up.h) {
                Tex x = new Tex(); x.id = upTex; x.w = up.w; x.h = up.h;
                Tex old = tex.get(up.page);
                if (old != null) GLES20.glDeleteTextures(1, new int[]{old.id}, 0);
                tex.put(up.page, x);
                Tex ot = thumbTex.get(up.page);
                if (ot != null) { GLES20.glDeleteTextures(1, new int[]{ot.id}, 0); thumbTex.remove(up.page); }
                fullPool.add(up.buf);
                if (!firstLogged) { firstLogged = true; Log.i(TAG, "first page texture ready, page " + target + ", " + (SystemClock.uptimeMillis() - created) + " ms after engine start"); }
                up = null;
            }
            int budget = (up != null && up.page == target) || (up == null && tex.get(target) == null) ? URGENT_BUDGET_US : UPLOAD_BUDGET_US;
            if ((System.nanoTime() - start) / 1000 >= budget) return;
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long nowNs = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0.016f : (nowNs - lastFrameNs) / 1e9f;
        lastFrameNs = nowNs;
        if (dt > 0.1f) dt = 0.1f;
        long nowMs = SystemClock.uptimeMillis();
        int ds, tgt; float p, dx, z, px, py;
        synchronized (lock) {
            int j = pendJump.getAndSet(-1);
            if (j >= 0 && j != target) jumpLocked(j);
            int t = pendTurn.getAndSet(0);
            while (t != 0) { int s = t > 0 ? 1 : -1; if (!turnLocked(s)) break; t -= s; }
            if (zoomAnim) {
                float k = (nowMs - zaStart) / 220f;
                if (k >= 1f) { k = 1f; zoomAnim = false; }
                float e = 1f - (1f - k) * (1f - k);
                float z1 = zaFrom + (zaTo - zaFrom) * e;
                panX = zaFx - (zaFx - zaPx) * (z1 / zaFrom); panY = zaFy - (zaFy - zaPy) * (z1 / zaFrom);
                zoom = z1; clampPan();
                if (zoom <= 1.001f) { zoom = 1f; panX = panY = 0; }
            }
            if (flinging && !dragging) {
                panX += velX * dt; panY += velY * dt;
                float mx = maxPanX(), my = maxPanY();
                if (panX > mx) { panX = mx; velX = 0; } else if (panX < -mx) { panX = -mx; velX = 0; }
                if (panY > my) { panY = my; velY = 0; } else if (panY < -my) { panY = -my; velY = 0; }
                float f = (float) Math.exp(-dt * 4.2f);
                velX *= f; velY *= f;
                if (Math.abs(velX) < 25 && Math.abs(velY) < 25) flinging = false;
            }
            float diff = target - pos;
            if (diff != 0f) {
                float step = SLIDE_SPEED * dt * Math.max(1f, Math.abs(diff) / 1.5f);
                if (Math.abs(diff) <= step) pos = target; else pos += diff > 0 ? step : -step;
            }
            ds = rtl ? -1 : 1; tgt = target; p = pos; dx = dragX; z = zoom; px = panX; py = panY;
        }
        if (needSchedule) { needSchedule = false; schedule(); }
        uploadThumbs();
        uploadFull();
        if (tgt != lastNotified) { lastNotified = tgt; host.onPage(tgt); }

        // fit size of the current page for pan bounds
        Tex ct = tex.get(tgt); if (ct == null) ct = thumbTex.get(tgt);
        float asp = ct != null ? (float) ct.w / ct.h : 0.708f;
        int W = vw, H = vh;
        float fw, fh;
        if (asp >= (float) W / H) { fw = W; fh = W / asp; } else { fh = H; fw = H * asp; }
        fitW = fw; fitH = fh;

        GLES20.glClearColor(0.04f, 0.04f, 0.04f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(prog);
        for (int i = tgt - 1; i <= tgt + 1; i++) {
            if (i < 0 || i >= n) continue;
            float x = ds * (i - p) * W + dx;
            if (x <= -W || x >= W) continue;
            Tex t = tex.get(i); if (t == null) t = thumbTex.get(i);
            if (t == null) continue;
            float a = (float) t.w / t.h, w2, h2;
            if (a >= (float) W / H) { w2 = W; h2 = W / a; } else { h2 = H; w2 = H * a; }
            float zz = i == tgt ? z : 1f, ox = x + (i == tgt ? px : 0f), oy = i == tgt ? py : 0f;
            GLES20.glUniform2f(uScale, w2 * zz / W, h2 * zz / H);
            GLES20.glUniform2f(uOff, 2f * ox / W, -2f * oy / H);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.id);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aUV);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }
}
