package com.litereader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Minimal file explorer: folders and supported files only. */
public class BrowserActivity extends Activity {
    File root, cur;
    TextView pathView;
    ListView lv;
    final ArrayList<File> items = new ArrayList<File>();
    boolean hasUp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Environment.getExternalStorageDirectory();
        String last = Store.lastDir(this);
        cur = last != null && new File(last).isDirectory() && last.startsWith(root.getAbsolutePath()) ? new File(last) : root;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(Ui.BG);
        TextView t = Ui.text(this, "File explorer", 24, Ui.FG, true);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), 0);
        v.addView(t);
        TextView n = Ui.text(this, "Supported: PDF, EPUB, MOBI/AZW3 (no DRM), DOCX, TXT, CBZ. Known but unsupported types (DjVu, CHM, old Word, KFX) are listed in grey with the reason.", 13, Ui.DIM, false);
        n.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 4));
        v.addView(n);
        pathView = Ui.text(this, "", 14, Ui.ACCENT, false);
        pathView.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        v.addView(pathView);
        lv = new ListView(this);
        lv.setDivider(null);
        lv.setBackgroundColor(Ui.BG);
        v.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(v);
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size() + (hasUp ? 1 : 0); }
            public Object getItem(int i) { return null; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View c, ViewGroup p) {
                if (hasUp && i == 0) return Ui.row(BrowserActivity.this, "..  (up)", null, true);
                File f = items.get(i - (hasUp ? 1 : 0));
                if (f.isDirectory()) return Ui.row(BrowserActivity.this, f.getName() + "/", null, true);
                boolean bad = Book.unsupportedMessage(f.getName()) != null;
                return Ui.row(BrowserActivity.this, f.getName(), bad ? "Not supported - tap for details" : Ui.size(f.length()), false, bad);
            }
        });
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int i, long id) {
                if (hasUp && i == 0) { up(); return; }
                File f = items.get(i - (hasUp ? 1 : 0));
                if (f.isDirectory()) { cur = f; load(); }
                else startActivity(new Intent(BrowserActivity.this, ReaderActivity.class).putExtra("path", f.getAbsolutePath()));
            }
        });
        load();
    }

    void up() {
        File p = cur.getParentFile();
        if (p != null && !cur.equals(root)) { cur = p; load(); }
    }

    void load() {
        Store.setLastDir(this, cur.getAbsolutePath());
        pathView.setText(cur.getAbsolutePath());
        items.clear();
        File[] fs = cur.listFiles();
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return Book.natural(a.getName(), b.getName());
                }
            });
            for (File f : fs) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory() || Book.listedName(f.getName())) items.add(f);
            }
        }
        hasUp = !cur.equals(root);
        ((BaseAdapter) lv.getAdapter()).notifyDataSetChanged();
        lv.setSelection(0);
    }

    @Override
    public void onBackPressed() { if (!cur.equals(root)) up(); else finish(); }
}
