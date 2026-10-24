package com.litereader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xFF121212, FG = 0xFFEEEEEE, DIM = 0xFF9A9A9A, ACCENT = 0xFFE0A030, ROW = 0xFF1C1C1C;

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String s) {
        TextView t = text(c, s, 16, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        t.setBackgroundColor(0xFF2A2A2A);
        t.setSingleLine(true);
        return t;
    }

    static String size(long b) {
        if (b >= 1L << 30) return String.format("%.2f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format("%.1f MB", b / (double) (1L << 20));
        return (b >> 10) + " KB";
    }

    static LinearLayout row(Context c, String title, String sub, boolean dir) { return row(c, title, sub, dir, false); }

    static LinearLayout row(Context c, String title, String sub, boolean dir, boolean grey) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        r.setBackgroundColor(ROW);
        TextView t = text(c, title, 18, dir ? ACCENT : grey ? DIM : FG, false);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.addView(t);
        if (sub != null) { TextView s = text(c, sub, 13, DIM, false); s.setSingleLine(true); r.addView(s); }
        return r;
    }

    static View header(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 13, ACCENT, true);
        t.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 6));
        t.setBackgroundColor(BG);
        return t;
    }
}
