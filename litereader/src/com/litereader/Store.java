package com.litereader;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

/** Reading positions, bookmarks, recent list, settings. Everything lives in one SharedPreferences file. */
final class Store {
    static class Recent { String key, path; int page, total; long time; }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("lr", Context.MODE_PRIVATE); }
    static String key(File f) { return f.getAbsolutePath() + "|" + f.length(); }

    static int getPage(Context c, String key) { return sp(c).getInt("p." + key, 0); }

    static void savePage(Context c, String key, String path, int page, int total, boolean commit) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putInt("p." + key, page).putInt("n." + key, total).putLong("t." + key, System.currentTimeMillis()).putString("f." + key, path);
        if (commit) e.commit(); else e.apply();
    }

    static ArrayList<Recent> recent(Context c) {
        SharedPreferences sp = sp(c);
        ArrayList<Recent> out = new ArrayList<Recent>();
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            String k = en.getKey();
            if (!k.startsWith("t.")) continue;
            String key = k.substring(2);
            String path = sp.getString("f." + key, null);
            if (path == null || !new File(path).isFile()) continue;
            Recent r = new Recent();
            r.key = key; r.path = path; r.time = ((Long) en.getValue()).longValue();
            r.page = sp.getInt("p." + key, 0); r.total = sp.getInt("n." + key, 0);
            out.add(r);
        }
        Collections.sort(out, new Comparator<Recent>() {
            public int compare(Recent a, Recent b) { return a.time < b.time ? 1 : (a.time > b.time ? -1 : 0); }
        });
        return out;
    }

    static void removeRecent(Context c, String key) {
        sp(c).edit().remove("t." + key).remove("f." + key).apply();
    }

    static int[] bookmarks(Context c, String key) {
        String s = sp(c).getString("b." + key, "");
        if (s.length() == 0) return new int[0];
        String[] p = s.split(",");
        int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i]);
        Arrays.sort(a);
        return a;
    }

    static boolean isBookmarked(Context c, String key, int page) {
        for (int b : bookmarks(c, key)) if (b == page) return true;
        return false;
    }

    /** returns true if the page is now bookmarked */
    static boolean toggleBookmark(Context c, String key, int page) {
        int[] a = bookmarks(c, key);
        StringBuilder sb = new StringBuilder();
        boolean had = false;
        for (int b : a) {
            if (b == page) { had = true; continue; }
            if (sb.length() > 0) sb.append(',');
            sb.append(b);
        }
        if (!had) { if (sb.length() > 0) sb.append(','); sb.append(page); }
        sp(c).edit().putString("b." + key, sb.toString()).commit();
        return !had;
    }

    static boolean rtl(Context c) { return sp(c).getBoolean("rtl", true); }
    static void setRtl(Context c, boolean v) { sp(c).edit().putBoolean("rtl", v).apply(); }
    static String lastDir(Context c) { return sp(c).getString("lastdir", null); }
    static void setLastDir(Context c, String d) { sp(c).edit().putString("lastdir", d).apply(); }
}
