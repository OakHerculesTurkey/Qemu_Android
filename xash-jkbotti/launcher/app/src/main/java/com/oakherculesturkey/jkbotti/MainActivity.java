package com.oakherculesturkey.jkbotti;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Starts Xash3D FWGS (su.xash.engine) with Half-Life + Metamod-FWGS + jk_botti
 * loaded from this APK's native library directory.
 *
 *   engine --(-dll @metamod)--> libmetamod_android_<arch>.so
 *   metamod --(MM_GAMEDLL)----> libhl_android_<arch>.so        (Half-Life DM)
 *   metamod --(MM_PLUGINS)----> libjk_botti_mm_android_<arch>.so
 *   engine --(gamelibdir)-----> libclient_android_<arch>.so    (HL client)
 */
public class MainActivity extends Activity {
    static final String[] ENGINE_PACKAGES = { "su.xash.engine.test", "su.xash.engine" };
    static final String ENGINE_ACTIVITY = "su.xash.engine.XashActivity";
    static final String ENGINE_RELEASES = "https://github.com/FWGS/xash3d-fwgs/releases/tag/continuous";

    private SharedPreferences prefs;
    private EditText argsEdit;
    private CheckBox botsCheck;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        // Started from Xash3D's own mod list: launch right away.
        if ("su.xash.engine.MOD".equals(getIntent().getAction())) {
            launch(prefs.getString("args", defaultArgs()), prefs.getBoolean("bots", true));
            finish();
            return;
        }

        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null)
            status.setText(describeState());
    }

    private static String defaultArgs() {
        return "-console -log";
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.subtitle);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(12);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);

        botsCheck = new CheckBox(this);
        botsCheck.setText(R.string.load_bots);
        botsCheck.setChecked(prefs.getBoolean("bots", true));
        root.addView(botsCheck);

        TextView argsLabel = new TextView(this);
        argsLabel.setText(R.string.extra_args);
        argsLabel.setPadding(0, dp(12), 0, 0);
        root.addView(argsLabel);

        argsEdit = new EditText(this);
        argsEdit.setSingleLine(true);
        argsEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        argsEdit.setText(prefs.getString("args", defaultArgs()));
        root.addView(argsEdit);

        Button start = new Button(this);
        start.setText(R.string.start);
        start.setOnClickListener(v -> {
            String args = argsEdit.getText().toString().trim();
            prefs.edit().putString("args", args).putBoolean("bots", botsCheck.isChecked()).apply();
            launch(args, botsCheck.isChecked());
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(start, lp);

        TextView help = new TextView(this);
        help.setText(R.string.help);
        help.setPadding(0, dp(16), 0, 0);
        help.setGravity(Gravity.START);
        help.setTextIsSelectable(true);
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String findEnginePackage() {
        PackageManager pm = getPackageManager();
        for (String p : ENGINE_PACKAGES) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** Finds lib<prefix>_android_<arch>.so in our native library directory. */
    private String findLib(String prefix) {
        File dir = new File(getApplicationInfo().nativeLibraryDir);
        String[] names = dir.list();
        if (names == null)
            return null;
        for (String n : names) {
            if (n.startsWith("lib" + prefix + "_android_") && n.endsWith(".so"))
                return new File(dir, n).getAbsolutePath();
        }
        return null;
    }

    private String describeState() {
        StringBuilder sb = new StringBuilder();
        String engine = findEnginePackage();
        sb.append(getString(R.string.engine)).append(": ")
                .append(engine != null ? engine : getString(R.string.engine_missing)).append('\n');
        for (String l : new String[] { "metamod", "hl", "client", "jk_botti_mm" }) {
            String p = findLib(l);
            sb.append(p != null ? "  ✓ " + new File(p).getName() : "  ✗ lib" + l + "_android_*.so").append('\n');
        }
        return sb.toString().trim();
    }

    private void launch(String userArgs, boolean bots) {
        String engine = findEnginePackage();
        if (engine == null) {
            Toast.makeText(this, R.string.engine_missing, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ENGINE_RELEASES)));
            } catch (ActivityNotFoundException ignored) {
            }
            return;
        }

        String libDir = getApplicationInfo().nativeLibraryDir;
        String hl = findLib("hl");
        String jk = findLib("jk_botti_mm");

        StringBuilder argv = new StringBuilder();
        ArrayList<String> env = new ArrayList<>();
        if (bots) {
            if (hl == null || jk == null || findLib("metamod") == null) {
                Toast.makeText(this, R.string.libs_missing, Toast.LENGTH_LONG).show();
                return;
            }
            argv.append("-dll @metamod ");
            env.add("MM_GAMEDLL");
            env.add(hl);
            env.add("MM_PLUGINS");
            env.add(jk);
        }
        argv.append(userArgs == null || userArgs.isEmpty() ? defaultArgs() : userArgs);

        Intent i = new Intent()
                .setComponent(new ComponentName(engine, ENGINE_ACTIVITY))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("gamedir", "valve")
                .putExtra("gamelibdir", libDir)
                .putExtra("package", getPackageName())
                .putExtra("argv", argv.toString().replaceAll("\\s+", " ").trim());
        if (!env.isEmpty())
            i.putExtra("env", env.toArray(new String[0]));

        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.launch_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
