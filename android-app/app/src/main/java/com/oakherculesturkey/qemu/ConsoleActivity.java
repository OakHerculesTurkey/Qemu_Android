package com.oakherculesturkey.qemu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oakherculesturkey.qemu.core.QmpClient;
import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Serial console, QEMU monitor (HMP over QMP) and the QEMU log of one VM. */
public class ConsoleActivity extends Activity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_TAB = "tab";
    static final int TAB_SERIAL = 0;
    static final int TAB_MONITOR = 1;
    static final int TAB_LOG = 2;

    private static final int MAX_CHARS = 200_000;
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]|\u001B[=>78cDEHM]");

    private String id;
    private VmConfig config;
    private int tab;
    private TextView text;
    private ScrollView scroll;
    private EditText input;
    private LinearLayout extraKeys;
    private final Button[] tabButtons = new Button[3];

    private final StringBuilder serialBuf = new StringBuilder();
    private final StringBuilder monitorBuf = new StringBuilder();
    private volatile LocalConn serialConn;
    private volatile boolean active;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean renderPosted;

    private final Runnable logRefresher = new Runnable() {
        @Override
        public void run() {
            if (tab == TAB_LOG && active) {
                showText(VmService.log(ConsoleActivity.this, id));
                ui.postDelayed(this, 1000);
            }
        }
    };

    public static void open(Context c, String id, int tab) {
        c.startActivity(new Intent(c, ConsoleActivity.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_TAB, tab));
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Button share = Ui.button(this, "Paylaş", v -> share());
        Button copy = Ui.button(this, "Kopyala", v -> Ui.copy(this, "QEMU", text.getText().toString()));
        root.addView(Ui.topBar(this, config.name, copy, share));

        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"Seri konsol", "Monitör", "Log"};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            tabButtons[i] = Ui.button(this, names[i], v -> selectTab(t));
            tabs.addView(tabButtons[i], new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabs);

        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        text.setTextIsSelectable(true);
        int p = Ui.dp(this, 8);
        text.setPadding(p, p, p, p);
        scroll = new ScrollView(this);
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        extraKeys = new LinearLayout(this);
        extraKeys.setOrientation(LinearLayout.VERTICAL);
        extraKeys.addView(Ui.keyRow(this,
                Ui.key(this, "Ctrl+C", v -> sendSerial("\u0003")),
                Ui.key(this, "Ctrl+D", v -> sendSerial("\u0004")),
                Ui.key(this, "Ctrl+Z", v -> sendSerial("\u001a")),
                Ui.key(this, "Tab", v -> sendSerial("\t")),
                Ui.key(this, "Esc", v -> sendSerial("\u001b")),
                Ui.key(this, "↑", v -> sendSerial("\u001b[A")),
                Ui.key(this, "↓", v -> sendSerial("\u001b[B")),
                Ui.key(this, "←", v -> sendSerial("\u001b[D")),
                Ui.key(this, "→", v -> sendSerial("\u001b[C")),
                Ui.key(this, "Enter", v -> sendSerial("\r")),
                Ui.key(this, "Temizle", v -> {
                    synchronized (serialBuf) {
                        serialBuf.setLength(0);
                    }
                    render();
                })));
        root.addView(extraKeys);

        LinearLayout inputRow = new LinearLayout(this);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                submit();
                return true;
            }
            return false;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        inputRow.addView(Ui.button(this, "Gönder", v -> submit()));
        root.addView(inputRow);

        Ui.applyInsets(root);
        setContentView(root);
        tab = getIntent().getIntExtra(EXTRA_TAB, TAB_SERIAL);
    }

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        new Thread(this::serialLoop, "serial").start();
        selectTab(tab);
    }

    @Override
    protected void onStop() {
        active = false;
        closeSerial();
        super.onStop();
    }

    private void selectTab(int t) {
        tab = t;
        for (int i = 0; i < 3; i++) {
            tabButtons[i].setTextColor(i == t ? Ui.ACCENT : 0xFF888888);
        }
        extraKeys.setVisibility(t == TAB_SERIAL ? View.VISIBLE : View.GONE);
        input.setVisibility(t == TAB_LOG ? View.GONE : View.VISIBLE);
        ((View) input.getParent()).setVisibility(t == TAB_LOG ? View.GONE : View.VISIBLE);
        input.setHint(t == TAB_SERIAL ? "Konsola yaz (Enter ile gönder)" : "Monitör komutu, ör: info status");
        ui.removeCallbacks(logRefresher);
        if (t == TAB_LOG) {
            logRefresher.run();
        } else {
            render();
        }
    }

    private void submit() {
        String s = input.getText().toString();
        input.setText("");
        if (tab == TAB_SERIAL) {
            sendSerial(s + "\r");
        } else if (tab == TAB_MONITOR) {
            runMonitor(s.trim());
        }
    }

    // ---- Serial ------------------------------------------------------------

    private void serialLoop() {
        VmService.Running r = VmService.get(id);
        synchronized (serialBuf) {
            serialBuf.setLength(0);
        }
        // Show what the guest printed before we connected (the log file).
        String earlier = Files.readText(VmStore.paths(this, config).serialLog(), 64 * 1024);
        appendSerial(earlier);
        if (r == null) {
            appendSerial("\n[Sanal makine çalışmıyor]\n");
            return;
        }
        try {
            LocalConn c = LocalConn.connect(r.paths.serialSocket(), 20000);
            serialConn = c;
            InputStream in = c.in;
            byte[] buf = new byte[4096];
            int n;
            while (active && (n = in.read(buf)) > 0) {
                appendSerial(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            if (active) appendSerial("\n[Seri bağlantı: " + e.getMessage() + "]\n");
        } finally {
            closeSerial();
        }
    }

    private void closeSerial() {
        LocalConn c = serialConn;
        serialConn = null;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void sendSerial(String s) {
        LocalConn c = serialConn;
        if (c == null) {
            Ui.toast(this, "Seri konsol bağlı değil");
            return;
        }
        new Thread(() -> {
            try {
                OutputStream o = c.out;
                o.write(s.getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (IOException e) {
                appendSerial("\n[Gönderilemedi: " + e.getMessage() + "]\n");
            }
        }).start();
    }

    private void appendSerial(String raw) {
        if (raw.isEmpty()) return;
        String s = ANSI.matcher(raw).replaceAll("");
        synchronized (serialBuf) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\r') continue;
                if (ch == '\b') {
                    if (serialBuf.length() > 0) serialBuf.setLength(serialBuf.length() - 1);
                } else if (ch == '\u0007') {
                    // bell
                } else {
                    serialBuf.append(ch);
                }
            }
            if (serialBuf.length() > MAX_CHARS) serialBuf.delete(0, serialBuf.length() - MAX_CHARS);
        }
        if (tab == TAB_SERIAL) render();
    }

    // ---- Monitor -----------------------------------------------------------

    private void runMonitor(String cmd) {
        if (cmd.isEmpty()) return;
        synchronized (monitorBuf) {
            monitorBuf.append("(qemu) ").append(cmd).append('\n');
        }
        render();
        new Thread(() -> {
            String out;
            try {
                out = QmpClient.describe(VmService.qmp(id, QmpClient.hmp(cmd)));
            } catch (IOException e) {
                out = "Hata: " + e.getMessage();
            }
            synchronized (monitorBuf) {
                monitorBuf.append(out.replace("\r", ""));
                if (!out.endsWith("\n")) monitorBuf.append('\n');
            }
            render();
        }).start();
    }

    // ---- Rendering ---------------------------------------------------------

    private void render() {
        synchronized (this) {
            if (renderPosted) return;
            renderPosted = true;
        }
        ui.postDelayed(() -> {
            synchronized (this) {
                renderPosted = false;
            }
            if (tab == TAB_SERIAL) {
                synchronized (serialBuf) {
                    showText(serialBuf.toString());
                }
            } else if (tab == TAB_MONITOR) {
                synchronized (monitorBuf) {
                    showText(monitorBuf.length() == 0
                            ? "QEMU monitör komutları (HMP). Örnekler:\n  info status\n  info block\n"
                            + "  info network\n  change cd0 /yol/dosya.iso\n  eject cd0\n"
                            + "  savevm durum1 / loadvm durum1 (qcow2 disk gerekir)\n  sendkey ctrl-alt-delete\n"
                            : monitorBuf.toString());
                }
            }
        }, 60);
    }

    private void showText(String s) {
        boolean atBottom = !scroll.canScrollVertically(1);
        text.setText(s);
        if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void share() {
        String body = "VM: " + config.name + "\n\n" + VmService.log(this, id)
                + "\n\n--- seri konsol ---\n"
                + Files.readText(VmStore.paths(this, config).serialLog(), 32 * 1024);
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "QEMU log: " + config.name)
                .putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(i, "Log'u paylaş"));
    }
}
