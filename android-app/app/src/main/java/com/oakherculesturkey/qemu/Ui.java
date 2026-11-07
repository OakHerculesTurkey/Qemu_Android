package com.oakherculesturkey.qemu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Shared UI helpers; the app uses framework widgets only (no AndroidX). */
final class Ui {
    private Ui() {}

    static final int ACCENT = 0xFF3D7BF7;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /**
     * Pads a view by the system bar / cutout / IME insets. Apps targeting
     * Android 15+ are always drawn edge-to-edge.
     */
    static void applyInsets(View v) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(),
                b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int il, it, ir, ib;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                il = i.left; it = i.top; ir = i.right; ib = i.bottom;
            } else {
                il = insets.getSystemWindowInsetLeft();
                it = insets.getSystemWindowInsetTop();
                ir = insets.getSystemWindowInsetRight();
                ib = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(l + il, t + it, r + ir, b + ib);
            return insets;
        });
        v.requestApplyInsets();
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    static void copy(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        toast(c, "Panoya kopyalandı");
    }

    static void message(Activity a, String title, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        int p = dp(a, 16);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);
        new AlertDialog.Builder(a).setTitle(title).setView(sv)
                .setPositiveButton("Tamam", null)
                .setNeutralButton("Kopyala", (d, w) -> copy(a, title, text))
                .show();
    }

    /** A title bar: title on the left, buttons on the right. */
    static LinearLayout topBar(Activity a, String title, View... actions) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(a, 12);
        bar.setPadding(p, dp(a, 6), dp(a, 4), dp(a, 6));
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        for (View v : actions) bar.addView(v);
        return bar;
    }

    static Button button(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    static Button primary(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(c, 20));
        b.setBackground(bg);
        int p = dp(c, 16);
        b.setPadding(p, 0, p, 0);
        b.setOnClickListener(l);
        return b;
    }

    /** Compact key-style button for the on-screen key rows. */
    static Button key(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(c, 44));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(c, 38));
        int p = dp(c, 10);
        b.setPadding(p, 0, p, 0);
        b.setBackground(keyBackground(c, false));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 38));
        lp.setMargins(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        b.setLayoutParams(lp);
        return b;
    }

    static GradientDrawable keyBackground(Context c, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? ACCENT : 0xCC333840);
        bg.setCornerRadius(dp(c, 8));
        return bg;
    }

    static HorizontalScrollView keyRow(Context c, View... keys) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View k : keys) row.addView(k);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }
}
