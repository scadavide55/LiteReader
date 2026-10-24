package com.litereader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.io.File;

public class ReaderActivity extends Activity implements Engine.Host, ReaderView.Listener {
    FrameLayout root;
    Book book;
    Engine engine;
    ReaderView view;
    TextView status, pageText, titleText, markBtn, dirBtn;
    LinearLayout top, bottom;
    SeekBar seek;
    final Handler ui = new Handler();
    volatile int curPage;
    volatile int thumbPct = 0;
    boolean hud, seeking, finishedOpen;

    final Runnable pageRun = new Runnable() {
        public void run() {
            updateHud();
            ui.removeCallbacks(saveRun);
            ui.postDelayed(saveRun, 500);
        }
    };
    final Runnable saveRun = new Runnable() {
        public void run() { if (book != null) Store.savePage(ReaderActivity.this, book.key, book.file.getAbsolutePath(), curPage, book.n, false); }
    };
    final Runnable hideRun = new Runnable() { public void run() { if (!seeking) setHud(false); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        status = Ui.text(this, "Opening...\n(text books are laid out on first open: a few seconds)", 18, Ui.FG, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        immersive();
        String p = getIntent().getStringExtra("path");
        if (p == null && getIntent().getData() != null) p = getIntent().getData().getPath();
        if (p == null) { finish(); return; }
        final File f = new File(p);
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Book bk = Book.open(ReaderActivity.this, f);
                    runOnUiThread(new Runnable() { public void run() { init(bk); } });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(ReaderActivity.this, e instanceof Book.Unsupported ? e.getMessage() : "Cannot open file: " + e, Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        }).start();
    }

    void immersive() {
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    void init(Book bk) {
        if (isFinishing()) return;
        book = bk;
        int start = Math.max(0, Math.min(bk.n - 1, Store.getPage(this, bk.key)));
        curPage = start;
        Log.i("LiteReader", "open " + bk.file.getName() + " pages=" + bk.n + " resume at page " + start);
        File th = new File(getFilesDir(), "th_" + Integer.toHexString(bk.key.hashCode()) + ".bin");
        engine = new Engine(bk, this, th, start, bk.mu != 0 ? false : Store.rtl(this));
        view = new ReaderView(this, engine, this);
        root.addView(view, 0);
        if (BuildFlags.SIM) registerSim();
        buildHud();
        Store.savePage(this, bk.key, bk.file.getAbsolutePath(), start, bk.n, false);
        finishedOpen = true;
        status.setText("Tap the centre for the menu");
        status.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        status.setPadding(0, 0, 0, Ui.dp(this, 40));
        status.setBackgroundColor(Color.TRANSPARENT);
        ui.postDelayed(new Runnable() { public void run() { status.setVisibility(View.GONE); } }, 2500);
        updateHud();
    }

    void buildHud() {
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setBackgroundColor(0xE6000000);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.button(this, "Library");
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        top.addView(back);
        titleText = Ui.text(this, book.title, 15, Ui.FG, false);
        titleText.setSingleLine(true); titleText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleText.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        top.addView(titleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        markBtn = Ui.button(this, "Mark");
        markBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleMark(); } });
        top.addView(markBtn);
        TextView marks = Ui.button(this, "Bookmarks");
        marks.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showMarks(); } });
        top.addView(marks);
        dirBtn = Ui.button(this, "");
        dirBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { boolean r = !engine.isRtl(); engine.setRtl(r); Store.setRtl(ReaderActivity.this, r); updateHud(); ui.removeCallbacks(hideRun); ui.postDelayed(hideRun, 4000); }
        });
        if (book.mu == 0) top.addView(dirBtn);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(top, tl);

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xE6000000);
        bottom.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 10));
        pageText = Ui.text(this, "", 15, Ui.FG, false);
        pageText.setGravity(Gravity.CENTER_HORIZONTAL);
        seek = new SeekBar(this);
        seek.setMax(Math.max(1, book.n - 1));
        seek.setProgress(curPage);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) { engine.requestJump(p); pageText.setText((p + 1) + " / " + book.n); }
            }
            public void onStartTrackingTouch(SeekBar s) { seeking = true; ui.removeCallbacks(hideRun); }
            public void onStopTrackingTouch(SeekBar s) { seeking = false; ui.postDelayed(hideRun, 4000); }
        });
        bottom.addView(seek);
        bottom.addView(pageText);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottom, bl);
        top.setVisibility(View.GONE); bottom.setVisibility(View.GONE);
    }

    void setHud(boolean on) {
        if (top == null) return;
        hud = on;
        top.setVisibility(on ? View.VISIBLE : View.GONE);
        bottom.setVisibility(on ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideRun);
        if (on) { updateHud(); ui.postDelayed(hideRun, 4000); }
    }

    void updateHud() {
        if (book == null || top == null) return;
        int p = curPage;
        boolean marked = Store.isBookmarked(this, book.key, p);
        markBtn.setText(marked ? "Marked" : "Mark");
        markBtn.setTextColor(marked ? Ui.ACCENT : Color.WHITE);
        dirBtn.setText(engine.isRtl() ? "Manga \u2190" : "Fumetto \u2192");
        if (!seeking) {
            seek.setProgress(p);
            pageText.setText((p + 1) + " / " + book.n + (thumbPct < 100 ? "     previews " + thumbPct + "%" : ""));
        }
    }

    void toggleMark() {
        if (book == null) return;
        Store.toggleBookmark(this, book.key, curPage);
        updateHud();
        ui.removeCallbacks(hideRun); ui.postDelayed(hideRun, 4000);
    }

    void showMarks() {
        final int[] bm = Store.bookmarks(this, book.key);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Bookmarks");
        if (bm.length == 0) b.setMessage("No bookmarks yet.\nOpen the menu and press Mark on a page.");
        else {
            String[] items = new String[bm.length];
            for (int i = 0; i < bm.length; i++) items[i] = "Page " + (bm[i] + 1) + "   (" + (100 * (bm[i] + 1) / book.n) + "%)";
            b.setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int i) { engine.requestJump(bm[i]); setHud(false); }
            });
        }
        b.setNegativeButton("Close", null);
        b.show();
    }

    void registerSim() {
        registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                String[] a = i.getStringExtra("a").split(" ");
                float f1 = a.length > 1 ? Float.parseFloat(a[1]) : 0, f2 = a.length > 2 ? Float.parseFloat(a[2]) : 0, f3 = a.length > 3 ? Float.parseFloat(a[3]) : 0;
                String c0 = a[0];
                if (c0.equals("down")) engine.touchDown();
                else if (c0.equals("drag")) engine.drag(f1, f2);
                else if (c0.equals("up")) engine.touchUp(f1, f2);
                else if (c0.equals("pinch")) engine.pinch(f1, f2, f3, 0, 0);
                else if (c0.equals("dtap")) engine.doubleTap(f1, f2);
                else if (c0.equals("tap")) engine.tap(f1);
                else if (c0.equals("jump")) engine.requestJump((int) f1);
                Log.i("LiteReader", "sim " + i.getStringExtra("a") + " -> page " + engine.page() + " zoom " + engine.zoom + " pan " + engine.panX + "," + engine.panY + " drag " + engine.dragX);
            }
        }, new IntentFilter("com.litereader.SIM"));
    }

    // ---- ReaderView.Listener ----
    public void onCenterTap() { setHud(!hud); }

    // ---- Engine.Host (GL thread) ----
    public void onPage(int page) { curPage = page; ui.post(pageRun); }
    public void onThumbProgress(int done, int total) {
        thumbPct = total == 0 ? 100 : (int) (100L * done / total);
        if (hud) ui.post(new Runnable() { public void run() { updateHud(); } });
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (engine == null) return super.onKeyDown(code, e);
        boolean rtl = engine.isRtl();
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_DOWN: engine.requestTurn(1); return true;
            case KeyEvent.KEYCODE_VOLUME_UP: engine.requestTurn(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: engine.requestTurn(rtl ? -1 : 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: engine.requestTurn(rtl ? 1 : -1); return true;
            case KeyEvent.KEYCODE_MENU: setHud(!hud); return true;
            case KeyEvent.KEYCODE_B: toggleMark(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (hud) { setHud(false); return true; }
                break;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) return true;
        return super.onKeyUp(code, e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) { view.onResume(); engine.resume(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (book != null && finishedOpen) {
            ui.removeCallbacks(saveRun);
            Store.savePage(this, book.key, book.file.getAbsolutePath(), curPage, book.n, true);
            Log.i("LiteReader", "saved page " + curPage);
        }
        if (view != null) { view.cancelPending(); view.onPause(); engine.pause(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.stop();
    }
}
