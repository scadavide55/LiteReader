package com.litereader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen page grid. Reads the engine's on-disk thumbnail cache directly (read-only), so it never touches the reading engine. */
final class ThumbGrid extends FrameLayout {
    interface Listener { void onPick(int page); void onClose(); }

    final int n, current;
    final File thumbFile;
    final Listener listener;
    final Handler ui = new Handler();
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(10 << 20) {
        protected int sizeOf(Integer k, Bitmap b) { return b.getRowBytes() * b.getHeight(); }
    };
    final HashSet<Integer> pending = new HashSet<Integer>();
    final String bookKey;
    final Context ctx;
    GridView grid;
    BaseAdapter adapter;
    volatile boolean closed;
    boolean refreshQueued;
    int[] marks;

    ThumbGrid(Context c, String bookKey, File thumbFile, int n, int current, int previewPct, Listener l) {
        super(c);
        ctx = c; this.bookKey = bookKey; this.thumbFile = thumbFile; this.n = n; this.current = current; listener = l;
        setBackgroundColor(Ui.BG);
        setClickable(true);                                  // swallow touches so the reader below never sees them
        marks = Store.bookmarks(c, bookKey);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0xFF000000);
        TextView close = Ui.button(c, "Close");
        close.setOnClickListener(new OnClickListener() { public void onClick(View v) { listener.onClose(); } });
        head.addView(close);
        TextView title = Ui.text(c, "Thumbnails" + (previewPct < 100 ? "   (previews " + previewPct + "% built)" : ""), 15, Ui.FG, false);
        title.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        head.addView(title);
        col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridView(c);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(c, 120));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(c, 8));
        grid.setVerticalSpacing(Ui.dp(c, 10));
        grid.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        grid.setClipToPadding(false);
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new BaseAdapter() {
            public int getCount() { return ThumbGrid.this.n; }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup parent) { return cell(i, v); }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int i, long id) { listener.onPick(i); }
        });
        col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        grid.setSelection(Math.max(0, current));
    }

    boolean marked(int p) { for (int m : marks) if (m == p) return true; return false; }

    View cell(int i, View v) {
        LinearLayout c;
        ImageView img; TextView num;
        if (v == null) {
            c = new LinearLayout(ctx);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            c.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 170)));
            num = Ui.text(ctx, "", 13, Ui.DIM, false);
            num.setGravity(Gravity.CENTER_HORIZONTAL);
            num.setPadding(0, Ui.dp(ctx, 3), 0, 0);
            c.addView(num, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            c.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 200)));
        } else {
            c = (LinearLayout) v;
            img = (ImageView) c.getChildAt(0); num = (TextView) c.getChildAt(1);
        }
        boolean cur = i == current;
        c.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2));
        c.setBackgroundColor(cur ? Ui.ACCENT : Ui.ROW);
        boolean m = marked(i);
        num.setText((i + 1) + (m ? " •" : ""));
        num.setTextColor(cur ? Color.BLACK : m ? Ui.ACCENT : Ui.DIM);
        Bitmap b = cache.get(i);
        img.setImageBitmap(b);
        if (b == null) request(i);
        return c;
    }

    void request(final int p) {
        synchronized (pending) { if (!pending.add(p)) return; }
        exec.execute(new Runnable() {
            public void run() {
                Bitmap b = null;
                try {
                    int first = grid.getFirstVisiblePosition(), last = grid.getLastVisiblePosition();
                    int span = Math.max(1, last - first + 1);
                    if (!closed && p >= first - span && p <= last + span) b = readSlot(thumbFile, p);   // skip rows the user already scrolled past
                } catch (Throwable ignored) {}
                synchronized (pending) { pending.remove(p); }
                if (b != null && !closed) { cache.put(p, b); refresh(); }
            }
        });
    }

    void refresh() {
        synchronized (pending) { if (refreshQueued) return; refreshQueued = true; }
        ui.postDelayed(new Runnable() {
            public void run() {
                synchronized (pending) { refreshQueued = false; }
                if (!closed) adapter.notifyDataSetChanged();
            }
        }, 80);
    }

    /** slot layout (see Engine.finishThumb): int magic, width, height, format (1 = RGB565, else 8-bit grey) then the pixels, in native byte order. */
    static Bitmap readSlot(File thumbFile, int page) throws Exception {
        RandomAccessFile f = new RandomAccessFile(thumbFile, "r");
        try {
            long pos = (long) page * Engine.SLOT;
            if (pos + 16 > f.length()) return null;
            byte[] h = new byte[16];
            f.seek(pos); f.readFully(h);
            ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.nativeOrder());
            if (hb.getInt(0) != Engine.MAGIC) return null;
            int w = hb.getInt(4), ht = hb.getInt(8), fmt = hb.getInt(12);
            int bpp = fmt == 1 ? 2 : 1;
            if (w <= 0 || ht <= 0 || (long) w * ht * bpp > Engine.SLOT - 16 || pos + 16 + (long) w * ht * bpp > f.length()) return null;
            byte[] px = new byte[w * ht * bpp];
            f.readFully(px);
            Bitmap bm = Bitmap.createBitmap(w, ht, Bitmap.Config.RGB_565);
            if (fmt == 1) {
                bm.copyPixelsFromBuffer(ByteBuffer.wrap(px));
            } else {
                ByteBuffer out = ByteBuffer.allocate(px.length * 2).order(ByteOrder.nativeOrder());
                java.nio.ShortBuffer sb = out.asShortBuffer();
                for (int i = 0; i < px.length; i++) { int g = px[i] & 255; sb.put((short) (((g >> 3) << 11) | ((g >> 2) << 5) | (g >> 3))); }
                bm.copyPixelsFromBuffer(out);
            }
            return bm;
        } finally { f.close(); }
    }

    void release() {
        closed = true;
        exec.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
