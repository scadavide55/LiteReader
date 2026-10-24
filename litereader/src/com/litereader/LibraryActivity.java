package com.litereader;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/** Home screen: recently opened books, then the Books and Documents folders, and a File explorer button. */
public class LibraryActivity extends Activity {
    LinearLayout list;
    TextView viewBtn;
    boolean gridMode;
    ArrayList<Item> lastRecents; ArrayList<String> lastNames; ArrayList<ArrayList<Item>> lastSections;
    volatile int gen = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        gridMode = Store.libGrid(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "LiteReader", 28, Ui.FG, true);
        title.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        viewBtn = Ui.button(this, "");
        viewBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                gridMode = !gridMode; Store.setLibGrid(LibraryActivity.this, gridMode);
                if (lastRecents != null) show(lastRecents, lastNames, lastSections);
            }
        });
        head.addView(viewBtn);
        root.addView(head);
        TextView notice = Ui.text(this, "Opens PDF, EPUB (text or comics), MOBI/AZW3 without DRM, DOCX, TXT and CBZ.", 13, Ui.DIM, false);
        notice.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 8));
        root.addView(notice);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView explorer = Ui.button(this, "File explorer");
        explorer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(LibraryActivity.this, BrowserActivity.class)); }
        });
        root.addView(explorer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        setContentView(root);
        // targetSdk 23+ asks for storage at runtime; on Android 4.0 the install-time grant still applies
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    protected void onResume() { super.onResume(); refresh(); }

    static class Item { String path, title, sub, key; boolean recent; }

    void refresh() {
        final int g = ++gen;
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<Item> recents = new ArrayList<Item>();
                for (Store.Recent r : Store.recent(LibraryActivity.this)) {
                    if (recents.size() >= 12) break;
                    Item it = new Item();
                    File f = new File(r.path);
                    it.path = r.path; it.key = r.key; it.recent = true;
                    it.title = stripExt(f.getName());
                    it.sub = (r.total > 0 ? "Page " + (r.page + 1) + " of " + r.total + "  (" + (100 * (r.page + 1) / r.total) + "%)   " : "") + Ui.size(f.length());
                    recents.add(it);
                }
                File root = Environment.getExternalStorageDirectory();
                final ArrayList<ArrayList<Item>> sections = new ArrayList<ArrayList<Item>>();
                final ArrayList<String> names = new ArrayList<String>();
                File[] top = root.listFiles();
                if (top != null) {
                    Arrays.sort(top);
                    for (String want : new String[]{"Books", "Documents"}) {
                        for (File d : top) {
                            if (d.isDirectory() && d.getName().equalsIgnoreCase(want)) {
                                ArrayList<Item> items = new ArrayList<Item>();
                                scan(d, 0, items);
                                names.add(want); sections.add(items);
                            }
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    public void run() { if (g == gen) show(recents, names, sections); }
                });
            }
        }).start();
    }

    static String stripExt(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

    void scan(File dir, int depth, ArrayList<Item> out) {
        File[] fs = dir.listFiles();
        if (fs == null || out.size() > 300) return;
        Arrays.sort(fs, new Comparator<File>() { public int compare(File a, File b) { return Book.natural(a.getName(), b.getName()); } });
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) { if (depth < 3) scan(f, depth + 1, out); }
            else if (Book.supportedName(f.getName())) {
                Item it = new Item();
                it.path = f.getAbsolutePath(); it.title = stripExt(f.getName()); it.sub = Ui.size(f.length());
                out.add(it);
            }
        }
    }

    void show(ArrayList<Item> recents, ArrayList<String> names, ArrayList<ArrayList<Item>> sections) {
        lastRecents = recents; lastNames = names; lastSections = sections;
        viewBtn.setText(gridMode ? "List" : "Thumbnails");
        covers++;                                            // invalidates a cover loader still running for the previous layout
        pendingCovers.clear();
        list.removeAllViews();
        if (!recents.isEmpty()) {
            list.addView(Ui.header(this, "Recent"));
            addItems(recents);
        }
        for (int i = 0; i < names.size(); i++) {
            list.addView(Ui.header(this, names.get(i)));
            if (sections.get(i).isEmpty()) {
                TextView t = Ui.text(this, "No supported files in this folder.", 14, Ui.DIM, false);
                t.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
                list.addView(t);
            }
            addItems(sections.get(i));
        }
        if (recents.isEmpty() && names.isEmpty()) {
            TextView t = Ui.text(this, "No Books or Documents folder found on this device.\nUse File explorer to pick a file.", 16, Ui.DIM, false);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 6));
            list.addView(t);
        }
        if (gridMode) loadCovers();
    }

    volatile int covers = 0;
    final ArrayList<Object[]> pendingCovers = new ArrayList<Object[]>();   // {Item, ImageView}

    void addItems(ArrayList<Item> items) {
        if (!gridMode) { for (Item it : items) addRow(it); return; }
        int cols = Math.max(2, getResources().getDisplayMetrics().widthPixels / Ui.dp(this, 150));
        for (int i = 0; i < items.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
            for (int c = 0; c < cols; c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i + c < items.size()) row.addView(cell(items.get(i + c)), lp);
                else row.addView(new View(this), lp);
            }
            list.addView(row);
        }
    }

    /** one book: white cover box (the first-page thumbnail once the book has been opened) and its name, cut with "..." when too long */
    View cell(final Item it) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        ImageView img = new ImageView(this);
        img.setBackgroundColor(Color.WHITE);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        c.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 170)));
        TextView name = Ui.text(this, it.title, 14, Ui.FG, false);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(LibraryActivity.this, ReaderActivity.class).putExtra("path", it.path)); }
        });
        if (it.recent) c.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { Store.removeRecent(LibraryActivity.this, it.key); refresh(); return true; }
        });
        pendingCovers.add(new Object[]{it, img});
        return c;
    }

    /** background: first-page thumbnails from the reader's cache, scaled small; the first 150 books only to keep the heap low */
    void loadCovers() {
        final int g = covers;
        final ArrayList<Object[]> work = new ArrayList<Object[]>(pendingCovers);
        new Thread(new Runnable() {
            public void run() {
                int done = 0;
                for (Object[] w : work) {
                    if (g != covers || done >= 150) return;
                    Item it = (Item) w[0];
                    final ImageView iv = (ImageView) w[1];
                    try {
                        String key = it.key != null ? it.key : Store.key(new File(it.path));
                        File th = new File(getFilesDir(), "th_" + Integer.toHexString(key.hashCode()) + ".bin");
                        if (!th.isFile()) continue;
                        Bitmap b = ThumbGrid.readSlot(th, 0);
                        if (b == null) continue;
                        int h = Ui.dp(LibraryActivity.this, 170);
                        final Bitmap s = b.getHeight() > h ? Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() * h / b.getHeight()), h, true) : b;
                        if (s != b) b.recycle();
                        done++;
                        final int gg = g;
                        runOnUiThread(new Runnable() { public void run() { if (gg == covers) iv.setImageBitmap(s); } });
                    } catch (Throwable ignored) {}
                }
            }
        }, "covers").start();
    }

    void addRow(final Item it) {
        View r = Ui.row(this, it.title, it.sub, false);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(LibraryActivity.this, ReaderActivity.class).putExtra("path", it.path)); }
        });
        if (it.recent) r.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { Store.removeRecent(LibraryActivity.this, it.key); refresh(); return true; }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 2;
        list.addView(r, lp);
    }
}
