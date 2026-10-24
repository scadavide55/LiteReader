package com.litereader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
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
    volatile int gen = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        TextView title = Ui.text(this, "LiteReader", 28, Ui.FG, true);
        title.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0);
        root.addView(title);
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
        list.removeAllViews();
        if (!recents.isEmpty()) {
            list.addView(Ui.header(this, "Recent"));
            for (Item it : recents) addRow(it);
        }
        for (int i = 0; i < names.size(); i++) {
            list.addView(Ui.header(this, names.get(i)));
            if (sections.get(i).isEmpty()) {
                TextView t = Ui.text(this, "No supported files in this folder.", 14, Ui.DIM, false);
                t.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
                list.addView(t);
            }
            for (Item it : sections.get(i)) addRow(it);
        }
        if (recents.isEmpty() && names.isEmpty()) {
            TextView t = Ui.text(this, "No Books or Documents folder found on this device.\nUse File explorer to pick a file.", 16, Ui.DIM, false);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 6));
            list.addView(t);
        }
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
