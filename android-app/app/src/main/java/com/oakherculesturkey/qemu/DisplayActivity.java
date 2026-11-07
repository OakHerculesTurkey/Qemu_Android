package com.oakherculesturkey.qemu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.oakherculesturkey.qemu.core.KeyLayouts;
import com.oakherculesturkey.qemu.core.Modifiers;
import com.oakherculesturkey.qemu.core.QmpClient;
import com.oakherculesturkey.qemu.core.RfbClient;
import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.IOException;

/**
 * Guest display: VNC view, a top bar, a quick-key row and a full on-screen
 * PC keyboard (keys Android keyboards lack: Esc, F1-F12, Ctrl/Alt/Win,
 * Ins/Home/End/PgUp/PgDn, shortcuts, mouse buttons).
 * Portrait: screen above, keyboard below. Landscape: keyboard over the screen.
 */
public class DisplayActivity extends Activity {
    static final String EXTRA_ID = "id";

    private static final int BAR_BG = 0xFF15181D;
    private static final int PAGE_MAIN = 0, PAGE_NAV = 1;

    private String id;
    private VmConfig config;
    private SharedPreferences prefs;

    private FrameLayout root;
    private LinearLayout column;
    private FrameLayout screen;
    private VncView vnc;
    private TextView status, zoomBadge;
    private LinearLayout topBar;
    private PcKeyboardView quickKeys;
    private LinearLayout kbdPanel;
    private PcKeyboardView keyboard;
    private Button pageMainB, pageNavB, layoutB;
    private ImageButton mouseB, showControlsB;

    private final Modifiers mods = new Modifiers();
    private boolean controlsVisible = true;
    private boolean keyboardOpen;
    private int page = PAGE_MAIN;
    private boolean turkishLabels;

    private volatile boolean active;
    private Thread connector;
    private volatile RfbClient client;
    private final Runnable vmListener = this::checkVm;

    public static void open(Context c, String id) {
        c.startActivity(new Intent(c, DisplayActivity.class).putExtra(EXTRA_ID, id));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = getIntent().getStringExtra(EXTRA_ID);
        config = VmStore.load(this, id);
        if (config == null) {
            finish();
            return;
        }
        prefs = getSharedPreferences("display", MODE_PRIVATE);
        turkishLabels = prefs.getBoolean("tr_" + id, "tr".equals(config.keymap));
        keyboardOpen = prefs.getBoolean("kbd_open", false);
        page = prefs.getInt("kbd_page", PAGE_MAIN);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        mods.setSink((sym, qnum, down) -> {
            RfbClient c = client;
            if (c != null) c.sendScancode(sym, qnum, down);
        });

        vnc = new VncView(this);
        vnc.setModifiers(mods);
        vnc.setTouchpad(prefs.getBoolean("touchpad", false));
        vnc.setListener(z -> updateZoomBadge());

        status = new TextView(this);
        status.setTextColor(0xFFB8C0CC);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 24);
        status.setPadding(p, p, p, p);
        status.setText("Bağlanıyor…");

        zoomBadge = new TextView(this);
        zoomBadge.setTextColor(Color.WHITE);
        zoomBadge.setTextSize(12);
        zoomBadge.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        zoomBadge.setBackground(pill(0xAA000000));
        zoomBadge.setOnClickListener(v -> vnc.resetZoom());
        zoomBadge.setVisibility(View.GONE);

        screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        screen.addView(vnc, new FrameLayout.LayoutParams(-1, -1));
        screen.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        zl.topMargin = Ui.dp(this, 8);
        screen.addView(zoomBadge, zl);

        topBar = buildTopBar();

        quickKeys = new PcKeyboardView(this);
        quickKeys.setup(mods, keyCallbacks);
        quickKeys.setRows(KeyLayouts.quick());

        kbdPanel = buildKeyboardPanel();

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BAR_BG);

        showControlsB = iconButton(R.drawable.ic_expand_more, "Kontrolleri göster", v -> setControlsVisible(true));
        showControlsB.setBackground(pill(0x99000000));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44),
                Gravity.TOP | Gravity.END);
        sl.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 8), 0);
        root.addView(showControlsB, sl);

        arrange();
        Ui.applyInsets(column);
        setContentView(root);
        hideSystemBars();
    }

    // ---- Layout ----------------------------------------------------------------

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** (Re)places the views for the current orientation and visibility state. */
    private void arrange() {
        detach(topBar);
        detach(screen);
        detach(quickKeys);
        detach(kbdPanel);
        column.removeAllViews();

        boolean land = landscape();
        float heightDp = getResources().getConfiguration().screenHeightDp;
        float row = land ? Math.max(28, Math.min(34, heightDp * 0.075f))
                : Math.max(36, Math.min(46, heightDp * 0.055f));
        keyboard.setRowHeight(Ui.dp(this, row));
        quickKeys.setRowHeight(Ui.dp(this, land ? 34 : 42));

        // Landscape with the keyboard open: no top bar, every pixel goes to
        // the screen and the keys (the keyboard has its own close button).
        if (controlsVisible && !(land && keyboardOpen)) column.addView(topBar);
        column.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        if (controlsVisible) {
            if (keyboardOpen) column.addView(kbdPanel);
            else column.addView(quickKeys);
        }
        showControlsB.setVisibility(controlsVisible ? View.GONE : View.VISIBLE);
        refreshPageButtons();
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        arrange();
        hideSystemBars();
    }

    private void setControlsVisible(boolean v) {
        controlsVisible = v;
        arrange();
    }

    private void setKeyboardOpen(boolean open) {
        keyboardOpen = open;
        prefs.edit().putBoolean("kbd_open", open).apply();
        if (open) hideImeKeyboard();
        arrange();
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR_BG);
        bar.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);

        bar.addView(iconButton(R.drawable.ic_back, "Geri", v -> finish()));
        TextView title = new TextView(this);
        title.setText(config.name);
        title.setTextColor(0xFFF2F4F7);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        bar.addView(iconButton(R.drawable.ic_keyboard, "PC klavyesi", v -> setKeyboardOpen(!keyboardOpen)));
        mouseB = iconButton(vnc.isTouchpad() ? R.drawable.ic_mouse : R.drawable.ic_touch,
                "Fare modu", v -> toggleMouseMode());
        bar.addView(mouseB);
        bar.addView(iconButton(R.drawable.ic_fit, "Ekrana sığdır", v -> vnc.resetZoom()));
        bar.addView(iconButton(R.drawable.ic_power, "Güç", this::showPower));
        bar.addView(iconButton(R.drawable.ic_more, "Diğer", this::showMore));
        bar.addView(iconButton(R.drawable.ic_expand_less, "Kontrolleri gizle", v -> setControlsVisible(false)));
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        return bar;
    }

    private LinearLayout buildKeyboardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF0F1114);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), 0);
        pageMainB = tab("Klavye", v -> setPage(PAGE_MAIN));
        pageNavB = tab("Gezinme · Kısayol · Fare", v -> setPage(PAGE_NAV));
        tabs.addView(pageMainB);
        tabs.addView(pageNavB);
        View spacer = new View(this);
        tabs.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        layoutB = tab(turkishLabels ? "TR" : "US", v -> {
            turkishLabels = !turkishLabels;
            prefs.edit().putBoolean("tr_" + id, turkishLabels).apply();
            layoutB.setText(turkishLabels ? "TR" : "US");
            setPage(page);
            Ui.toast(this, "Tuş etiketleri: " + (turkishLabels ? "Türkçe Q" : "İngilizce (ABD)")
                    + ". Çıkan karakteri misafirin klavye düzeni belirler.");
        });
        tabs.addView(layoutB);
        ImageButton ime = iconButton(R.drawable.ic_keyboard, "Android klavyesi (metin yazmak için)", v -> toggleImeKeyboard());
        ImageButton close = iconButton(R.drawable.ic_expand_more, "Klavyeyi kapat", v -> setKeyboardOpen(false));
        int small = Ui.dp(this, 36);
        ime.setLayoutParams(new LinearLayout.LayoutParams(small, small));
        close.setLayoutParams(new LinearLayout.LayoutParams(small, small));
        tabs.addView(ime);
        tabs.addView(close);
        panel.addView(tabs);

        keyboard = new PcKeyboardView(this);
        keyboard.setup(mods, keyCallbacks);
        panel.addView(keyboard);
        setPage(page);
        return panel;
    }

    private void setPage(int p) {
        page = p;
        prefs.edit().putInt("kbd_page", p).apply();
        keyboard.setRows(p == PAGE_NAV ? KeyLayouts.nav() : KeyLayouts.full(turkishLabels));
        refreshPageButtons();
    }

    private void refreshPageButtons() {
        if (pageMainB == null) return;
        pageMainB.setTextColor(page == PAGE_MAIN ? Color.WHITE : 0xFF8A93A0);
        pageNavB.setTextColor(page == PAGE_NAV ? Color.WHITE : 0xFF8A93A0);
        pageMainB.setBackground(page == PAGE_MAIN ? pill(0xFF2B3038) : null);
        pageNavB.setBackground(page == PAGE_NAV ? pill(0xFF2B3038) : null);
    }

    private Button tab(String text, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(0xFF8A93A0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(Ui.dp(this, 32));
        b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        b.setOnClickListener(l);
        return b;
    }

    private ImageButton iconButton(int icon, String desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this, null, android.R.attr.borderlessButtonStyle);
        b.setImageResource(icon);
        b.setContentDescription(desc);
        b.setTooltipText(desc);
        b.setOnClickListener(l);
        int s = Ui.dp(this, 44);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        return b;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Ui.dp(this, 18));
        return d;
    }

    private void updateZoomBadge() {
        float z = vnc.getZoom();
        if (z <= 1.01f) {
            zoomBadge.setVisibility(View.GONE);
        } else {
            zoomBadge.setText(String.format(java.util.Locale.ROOT, "%.1f×  ·  sığdırmak için dokun", z));
            zoomBadge.setVisibility(View.VISIBLE);
        }
    }

    private void toggleMouseMode() {
        boolean tp = !vnc.isTouchpad();
        vnc.setTouchpad(tp);
        prefs.edit().putBoolean("touchpad", tp).apply();
        mouseB.setImageResource(tp ? R.drawable.ic_mouse : R.drawable.ic_touch);
        Ui.toast(this, tp ? "Touchpad modu: parmakla imleci sürükleyin, dokunarak tıklayın"
                : "Dokunma modu: dokunduğunuz yere tıklanır");
    }

    // ---- On-screen key handling ---------------------------------------------

    private final PcKeyboardView.Callbacks keyCallbacks = new PcKeyboardView.Callbacks() {
        @Override
        public void onKey(KeyLayouts.Key k, boolean down) {
            vnc.scancode(k.keysym, k.qnum, down);
        }

        @Override
        public void onCombo(KeyLayouts.Key k) {
            RfbClient c = client;
            if (c == null) return;
            for (int m : k.comboMods) c.sendScancode(Modifiers.SYMS[m], Modifiers.QNUMS[m], true);
            c.sendScancode(k.keysym, k.qnum, true);
            c.sendScancode(k.keysym, k.qnum, false);
            for (int i = k.comboMods.length - 1; i >= 0; i--) {
                int m = k.comboMods[i];
                if (!mods.active(m)) c.sendScancode(Modifiers.SYMS[m], Modifiers.QNUMS[m], false);
            }
        }

        @Override
        public void onMouse(KeyLayouts.Key k, boolean down) {
            if (k.arg == KeyLayouts.MOUSE_WHEEL_UP) {
                if (down) vnc.wheel(1);
            } else if (k.arg == KeyLayouts.MOUSE_WHEEL_DOWN) {
                if (down) vnc.wheel(-1);
            } else {
                vnc.mouseButton(k.arg, down);
            }
        }

        @Override
        public void onAction(KeyLayouts.Key k) {
            if (k.arg == KeyLayouts.ACTION_ANDROID_IME) toggleImeKeyboard();
        }
    };

    private void hideImeKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(vnc.getWindowToken(), 0);
    }

    /** Android's own keyboard, for typing text (accents, emoji, dictation). */
    private void toggleImeKeyboard() {
        vnc.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets wi = vnc.getRootWindowInsets();
            boolean visible = wi != null && wi.isVisible(WindowInsets.Type.ime());
            if (visible) {
                imm.hideSoftInputFromWindow(vnc.getWindowToken(), 0);
            } else {
                if (keyboardOpen) {
                    keyboardOpen = false;
                    arrange();
                }
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) c.show(WindowInsets.Type.ime());
                imm.showSoftInput(vnc, InputMethodManager.SHOW_IMPLICIT);
            }
        } else {
            //noinspection deprecation
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    // ---- Menus ----------------------------------------------------------------

    private void showPower(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 1, "Duraklat");
        pm.getMenu().add(0, 2, 2, "Devam et");
        pm.getMenu().add(0, 3, 3, "Yeniden başlat (reset)");
        pm.getMenu().add(0, 4, 4, "Kapat (ACPI)");
        pm.getMenu().add(0, 5, 5, "Zorla kapat");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: qmp(QmpClient.command("stop")); break;
                case 2: qmp(QmpClient.command("cont")); break;
                case 3: qmp(QmpClient.command("system_reset")); break;
                case 4: qmp(QmpClient.command("system_powerdown")); break;
                case 5: VmService.stop(id); break;
            }
            return true;
        });
        pm.show();
    }

    private void showMore(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 1, "Seri konsol");
        pm.getMenu().add(0, 2, 2, "QEMU monitörü");
        pm.getMenu().add(0, 3, 3, "Log");
        pm.getMenu().add(0, 4, 4, "Değiştirici tuşları bırak");
        pm.getMenu().add(0, 5, 5, "Nasıl kullanılır?");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: ConsoleActivity.open(this, id, ConsoleActivity.TAB_SERIAL); break;
                case 2: ConsoleActivity.open(this, id, ConsoleActivity.TAB_MONITOR); break;
                case 3: ConsoleActivity.open(this, id, ConsoleActivity.TAB_LOG); break;
                case 4: mods.reset(true); break;
                case 5: help(); break;
            }
            return true;
        });
        pm.show();
    }

    private void help() {
        Ui.message(this, "Ekran kontrolleri",
                "FARE\n"
                + "• Dokunma modu: dokunduğun yere tıklar; uzun bas = sağ tık; sürükle = sol tuşla sürükle.\n"
                + "• Touchpad modu: parmakla imleci taşı; dokun = tık; iki parmakla dokun = sağ tık; "
                + "uzun bas ve sürükle = sürükle.\n"
                + "• İki parmak: kıstır = yakınlaştır, kaydır = fare tekerleği (yakınken: ekranı kaydır).\n\n"
                + "KLAVYE\n"
                + "• Üstteki klavye simgesi PC klavyesini açar: Esc, F1–F12, Ctrl, Alt, Win, Ins, Home, "
                + "End, PgUp/PgDn, kısayollar ve fare tuşları.\n"
                + "• Ctrl/Alt/Shift/Win: bir dokunuş = sonraki tuşa uygulanır, iki dokunuş = kilitli, "
                + "basılı tutup başka tuşa basmak da olur.\n"
                + "• Tuşlar gerçek PC tuş kodu gönderir; çıkan karakteri misafir işletim sisteminin "
                + "klavye düzeni belirler (TR/US düğmesi sadece etiketleri değiştirir).\n"
                + "• Metin yazmak için PC klavyesindeki ⌨ tuşu Android klavyesini açar.");
    }

    private void qmp(String json) {
        new Thread(() -> {
            String msg;
            try {
                msg = QmpClient.describe(VmService.qmp(id, json));
            } catch (IOException e) {
                msg = "Hata: " + e.getMessage();
            }
            String m = msg;
            runOnUiThread(() -> Ui.toast(this, m));
        }).start();
    }

    // ---- System bars -------------------------------------------------------

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ---- Connection --------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        VmService.addListener(vmListener);
        connector = new Thread(this::connectLoop, "vnc");
        connector.start();
    }

    @Override
    protected void onStop() {
        active = false;
        VmService.removeListener(vmListener);
        mods.reset(true);
        RfbClient c = client;
        if (c != null) c.close();
        if (connector != null) connector.interrupt();
        super.onStop();
    }

    private void checkVm() {
        if (!VmService.isRunning(id)) {
            setStatus("Sanal makine kapandı.\nAyrıntılar için ⋮ → Log.");
        }
    }

    private void setStatus(String s) {
        runOnUiThread(() -> {
            status.setText(s);
            status.setVisibility(s == null ? View.GONE : View.VISIBLE);
        });
    }

    private void connectLoop() {
        // The service may still be processing the start request.
        VmService.Running r = null;
        for (int i = 0; i < 50 && active && (r = VmService.get(id)) == null; i++) {
            if (!sleep(100)) return;
        }
        if (r == null) {
            setStatus("Sanal makine çalışmıyor.");
            return;
        }
        while (active && VmService.isRunning(id)) {
            LocalConn conn = null;
            IOException last = null;
            // Retry while QEMU starts up, but stop as soon as it has exited.
            for (int i = 0; i < 90 && active && VmService.isRunning(id) && conn == null; i++) {
                try {
                    conn = LocalConn.connect(r.paths.vncSocket(), 0);
                } catch (IOException e) {
                    last = e;
                    if (!sleep(500)) return;
                }
            }
            if (conn == null) {
                if (!active) return;
                if (!VmService.isRunning(id)) break;
                setStatus("VNC'ye bağlanılamadı: " + (last != null ? last.getMessage() : "")
                        + "\nAyrıntılar için ⋮ → Log.");
                return;
            }
            final LocalConn lc = conn;
            RfbClient c = new RfbClient(lc.in, lc.out, lc, new RfbClient.Listener() {
                @Override
                public void onConnected(String name, int w, int h) {
                    setStatus(null);
                }

                @Override
                public void onResize(int w, int h) {
                    vnc.onResize(w, h);
                }

                @Override
                public void onUpdate(int[] fb, int fbWidth, int x, int y, int w, int h) {
                    vnc.onUpdate(fb, fbWidth, x, y, w, h);
                }

                @Override
                public void onFrameComplete() {
                    vnc.onFrameComplete();
                }

                @Override
                public void onBell() {
                }

                @Override
                public void onDisconnected(Exception cause) {
                    if (active && cause != null) setStatus("Bağlantı koptu: " + cause.getMessage());
                }
            });
            client = c;
            vnc.setClient(c);
            mods.reset(false);
            c.run(); // blocks until disconnected
            vnc.setClient(null);
            client = null;
            if (!active || !sleep(800)) return;
        }
        if (active) checkVm();
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
