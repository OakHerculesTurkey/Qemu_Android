package com.oakherculesturkey.qemu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.oakherculesturkey.qemu.core.Keysyms;
import com.oakherculesturkey.qemu.core.QmpClient;
import com.oakherculesturkey.qemu.core.RfbClient;
import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Full-screen guest display (VNC over a private unix socket) with a key/tool bar. */
public class DisplayActivity extends Activity {
    static final String EXTRA_ID = "id";

    private String id;
    private VmConfig config;
    private VncView vnc;
    private TextView status;
    private LinearLayout toolbar;
    private volatile boolean active;
    private Thread connector;
    private volatile RfbClient client;

    private boolean ctrl, alt, win, shift;
    private Button ctrlB, altB, winB, shiftB, mouseB;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        vnc = new VncView(this);
        vnc.setModifierSource(this::takeModifiers);
        root.addView(vnc, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setText("Bağlanıyor…");
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        toolbar = buildToolbar();
        overlay.addView(toolbar);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        Button toggle = Ui.key(this, "☰", v -> toolbar.setVisibility(
                toolbar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, Ui.dp(this, 38),
                Gravity.TOP | Gravity.END);
        tl.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 6), 0);
        root.addView(toggle, tl);

        Ui.applyInsets(overlay);
        // Keep the top-right toggle clear of the status bar / cutout
        toggle.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                    : ins.getSystemWindowInsetTop();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            int want = top + Ui.dp(this, 6);
            if (lp.topMargin != want) {
                lp.topMargin = want;
                v.setLayoutParams(lp);
            }
            return ins;
        });
        setContentView(root);
        hideSystemBars();
    }

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

    private LinearLayout buildToolbar() {
        List<View> keys = new ArrayList<>();
        keys.add(Ui.key(this, "⌨", v -> toggleKeyboard()));
        mouseB = Ui.key(this, "Dokunma", v -> {
            vnc.setTouchpad(!vnc.isTouchpad());
            mouseB.setText(vnc.isTouchpad() ? "Touchpad" : "Dokunma");
        });
        keys.add(mouseB);
        keys.add(Ui.key(this, "Esc", v -> vnc.tap(Keysyms.ESCAPE)));
        keys.add(Ui.key(this, "Tab", v -> vnc.tap(Keysyms.TAB)));
        ctrlB = Ui.key(this, "Ctrl", v -> { ctrl = !ctrl; refreshMods(); });
        altB = Ui.key(this, "Alt", v -> { alt = !alt; refreshMods(); });
        shiftB = Ui.key(this, "Shift", v -> { shift = !shift; refreshMods(); });
        winB = Ui.key(this, "Win", v -> { win = !win; refreshMods(); });
        keys.add(ctrlB);
        keys.add(altB);
        keys.add(shiftB);
        keys.add(winB);
        keys.add(Ui.key(this, "←", v -> vnc.tap(Keysyms.LEFT)));
        keys.add(Ui.key(this, "↑", v -> vnc.tap(Keysyms.UP)));
        keys.add(Ui.key(this, "↓", v -> vnc.tap(Keysyms.DOWN)));
        keys.add(Ui.key(this, "→", v -> vnc.tap(Keysyms.RIGHT)));
        keys.add(Ui.key(this, "F…", this::showFKeys));
        keys.add(Ui.key(this, "Ctrl+Alt+Del", v ->
                vnc.tapCombo(Keysyms.DELETE, Keysyms.CONTROL_L, Keysyms.ALT_L)));
        keys.add(Ui.key(this, "Konsol", v -> ConsoleActivity.open(this, id, ConsoleActivity.TAB_SERIAL)));
        keys.add(Ui.key(this, "Monitör", v -> ConsoleActivity.open(this, id, ConsoleActivity.TAB_MONITOR)));
        keys.add(Ui.key(this, "Log", v -> ConsoleActivity.open(this, id, ConsoleActivity.TAB_LOG)));
        keys.add(Ui.key(this, "Güç…", this::showPower));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0x99000000);
        int p = Ui.dp(this, 2);
        bar.setPadding(p, p, p, p);
        bar.addView(Ui.keyRow(this, keys.toArray(new View[0])));
        return bar;
    }

    private void refreshMods() {
        ctrlB.setBackground(Ui.keyBackground(this, ctrl));
        altB.setBackground(Ui.keyBackground(this, alt));
        shiftB.setBackground(Ui.keyBackground(this, shift));
        winB.setBackground(Ui.keyBackground(this, win));
    }

    /** One-shot modifiers: consumed by the next key. */
    private int[] takeModifiers() {
        List<Integer> m = new ArrayList<>();
        if (ctrl) m.add(Keysyms.CONTROL_L);
        if (alt) m.add(Keysyms.ALT_L);
        if (shift) m.add(Keysyms.SHIFT_L);
        if (win) m.add(Keysyms.SUPER_L);
        int[] r = new int[m.size()];
        for (int i = 0; i < r.length; i++) r[i] = m.get(i);
        if (r.length > 0) {
            ctrl = alt = shift = win = false;
            runOnUiThread(this::refreshMods);
        }
        return r;
    }

    private void toggleKeyboard() {
        vnc.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets wi = vnc.getRootWindowInsets();
            boolean visible = wi != null && wi.isVisible(WindowInsets.Type.ime());
            if (visible) {
                imm.hideSoftInputFromWindow(vnc.getWindowToken(), 0);
            } else {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) c.show(WindowInsets.Type.ime());
                imm.showSoftInput(vnc, InputMethodManager.SHOW_IMPLICIT);
            }
        } else {
            //noinspection deprecation
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void showFKeys(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        for (int i = 1; i <= 12; i++) pm.getMenu().add(0, i, i, "F" + i);
        pm.getMenu().add(0, 100, 100, "Alt+F4");
        pm.getMenu().add(0, 101, 101, "Ctrl+Alt+F1");
        pm.getMenu().add(0, 102, 102, "Ctrl+Alt+F2");
        pm.getMenu().add(0, 103, 103, "Print Screen");
        pm.setOnMenuItemClickListener(item -> {
            int i = item.getItemId();
            if (i <= 12) vnc.tap(Keysyms.f(i));
            else if (i == 100) vnc.tapCombo(Keysyms.f(4), Keysyms.ALT_L);
            else if (i == 101) vnc.tapCombo(Keysyms.f(1), Keysyms.CONTROL_L, Keysyms.ALT_L);
            else if (i == 102) vnc.tapCombo(Keysyms.f(2), Keysyms.CONTROL_L, Keysyms.ALT_L);
            else if (i == 103) vnc.tap(Keysyms.PRINT);
            return true;
        });
        pm.show();
    }

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
        RfbClient c = client;
        if (c != null) c.close();
        if (connector != null) connector.interrupt();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void checkVm() {
        if (!VmService.isRunning(id)) {
            setStatus("Sanal makine kapandı.\nAyrıntılar için Log'a bakın.");
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
                        + "\nLog'a bakın.");
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ViewGroup content = findViewById(android.R.id.content);
        if (content != null) content.removeAllViews();
    }
}
