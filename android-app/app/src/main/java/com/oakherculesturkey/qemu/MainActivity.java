package com.oakherculesturkey.qemu;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.File;
import java.io.InputStream;
import java.util.List;

/** VM list. */
public class MainActivity extends Activity {
    private LinearLayout list;
    private final Runnable vmListener = this::refresh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Button add = Ui.pill(this, "Yeni", R.drawable.ic_add, Ui.ACCENT, v -> chooseTemplate());
        android.widget.ImageButton more = new android.widget.ImageButton(this, null,
                android.R.attr.borderlessButtonStyle);
        more.setImageResource(R.drawable.ic_more);
        more.setColorFilter(getColor(R.color.text_primary));
        more.setContentDescription("Menü");
        more.setOnClickListener(this::showMenu);
        more.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout bar = Ui.topBar(this, "QEMU", add, more);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 4));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        list.setPadding(p, 0, p, p * 2);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        Ui.applyInsets(root);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        prepareData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        VmService.addListener(vmListener);
        refresh();
    }

    @Override
    protected void onPause() {
        VmService.removeListener(vmListener);
        super.onPause();
    }

    /** Extracts firmware/keymaps on first start (and after each update). */
    private void prepareData() {
        if (Env.dataReady(this)) return;
        ProgressBar bar = new ProgressBar(this);
        int p = Ui.dp(this, 20);
        bar.setPadding(p, p, p, p);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("QEMU dosyaları hazırlanıyor…")
                .setMessage("İlk açılışta bir kez yapılır.").setView(bar).setCancelable(false).show();
        new Thread(() -> {
            String err = null;
            try {
                Env.ensureData(this);
            } catch (Exception e) {
                err = e.toString();
            }
            String error = err;
            runOnUiThread(() -> {
                dlg.dismiss();
                if (error != null) Ui.message(this, "Hazırlık hatası", error);
            });
        }).start();
    }

    private void refresh() {
        list.removeAllViews();
        List<VmConfig> vms = VmStore.list(this);
        if (vms.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Henüz sanal makine yok.\n\n\"+ Yeni\" ile bir şablon seçin, ardından bir ISO "
                    + "veya disk dosyası ekleyin.\n\nİpucu: ARM64 misafirler (ör. Alpine, Debian, "
                    + "Ubuntu ARM64) bu telefonda x86'ya göre çok daha hızlı çalışır.");
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 48), 0, Ui.dp(this, 16));
            list.addView(t);
            Button b = Ui.primary(this, "Yeni sanal makine", v -> chooseTemplate());
            list.addView(b, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
            return;
        }
        for (VmConfig vm : vms) list.addView(row(vm));
    }

    private View row(VmConfig vm) {
        boolean running = VmService.isRunning(vm.id);
        boolean vncDisplay = "vnc".equals(vm.display);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, Ui.dp(this, 8), Ui.dp(this, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 18));
        bg.setColor(getColor(R.color.card_bg));
        bg.setStroke(Ui.dp(this, running ? 2 : 1), running ? getColor(R.color.running) : getColor(R.color.card_stroke));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 12);
        card.setLayoutParams(lp);

        // header: arch badge, name, status
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        boolean arm = vm.isAarch64();
        TextView badge = Ui.chip(this, arm ? "ARM64" : "x86", 0xFFFFFFFF, arm ? 0xFF2F8F6A : 0xFF7A5AF8);
        head.addView(badge);
        TextView title = new TextView(this);
        title.setText(vm.name);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (running) head.addView(Ui.chip(this, "● Çalışıyor", 0xFFFFFFFF, getColor(R.color.running)));
        card.addView(head);

        TextView sub = new TextView(this);
        String media = !vm.cdrom.isEmpty() ? "ISO · " + new File(vm.cdrom).getName()
                : !vm.disk.isEmpty() ? "Disk · " + new File(vm.disk).getName()
                : !vm.kernel.isEmpty() ? "Kernel: " + new File(vm.kernel).getName() : "Medya seçilmedi";
        sub.setText(vm.smp + " çekirdek · " + memText(vm.memMb) + " · "
                + (vncDisplay ? "ekran" : "seri konsol") + "\n" + media);
        sub.setTextSize(13);
        sub.setTextColor(getColor(R.color.text_secondary));
        sub.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        sub.setLineSpacing(0, 1.15f);
        card.addView(sub);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        if (running) {
            actions.addView(Ui.pill(this, "Durdur", R.drawable.ic_stop, 0xFFD14343, v -> confirmStop(vm)));
            actions.addView(Ui.pill(this, "Konsol", R.drawable.ic_terminal, 0xFF4A5160, v ->
                    ConsoleActivity.open(this, vm.id, ConsoleActivity.TAB_SERIAL)));
            if (vncDisplay) {
                actions.addView(Ui.pill(this, "Ekran", R.drawable.ic_monitor, Ui.ACCENT, v ->
                        DisplayActivity.open(this, vm.id)));
            }
        } else {
            actions.addView(Ui.pill(this, "Başlat", R.drawable.ic_play, Ui.ACCENT, v -> startVm(vm)));
        }
        android.widget.ImageButton more = new android.widget.ImageButton(this, null,
                android.R.attr.borderlessButtonStyle);
        more.setImageResource(R.drawable.ic_more);
        more.setColorFilter(getColor(R.color.text_secondary));
        more.setContentDescription("Diğer");
        more.setOnClickListener(v -> vmMenu(v, vm));
        actions.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = Ui.dp(this, 6);
        card.addView(actions, al);
        card.setOnClickListener(v -> {
            if (!running) EditVmActivity.openEdit(this, vm.id);
            else if (vncDisplay) DisplayActivity.open(this, vm.id);
            else ConsoleActivity.open(this, vm.id, ConsoleActivity.TAB_SERIAL);
        });
        return card;
    }

    private static String memText(int mb) {
        return mb >= 1024 && mb % 1024 == 0 ? (mb / 1024) + " GB RAM" : mb + " MB RAM";
    }

    private void startVm(VmConfig vm) {
        if (!Env.dataReady(this)) {
            Ui.toast(this, "QEMU dosyaları hâlâ hazırlanıyor, birkaç saniye bekleyin");
            return;
        }
        VmService.start(this, vm.id);
        list.postDelayed(() -> {
            if ("vnc".equals(vm.display)) DisplayActivity.open(this, vm.id);
            else ConsoleActivity.open(this, vm.id, ConsoleActivity.TAB_SERIAL);
        }, 500);
    }

    private void confirmStop(VmConfig vm) {
        new AlertDialog.Builder(this).setTitle(vm.name + " durdurulsun mu?")
                .setMessage("Kaydedilmemiş veriler kaybolabilir. Misafiri içeriden kapatmak daha güvenlidir "
                        + "(Ekran → Güç → Kapat (ACPI)).")
                .setPositiveButton("Durdur", (d, w) -> VmService.stop(vm.id))
                .setNegativeButton("İptal", null).show();
    }

    private void vmMenu(View anchor, VmConfig vm) {
        boolean running = VmService.isRunning(vm.id);
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 1, "Düzenle").setEnabled(!running);
        pm.getMenu().add(0, 2, 2, "Kopyasını oluştur");
        pm.getMenu().add(0, 3, 3, "Log");
        pm.getMenu().add(0, 4, 4, "Monitör").setEnabled(running);
        pm.getMenu().add(0, 5, 5, "Dosyalar");
        pm.getMenu().add(0, 6, 6, "Sil").setEnabled(!running);
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: EditVmActivity.openEdit(this, vm.id); break;
                case 2: EditVmActivity.openCopy(this, vm.id); break;
                case 3: ConsoleActivity.open(this, vm.id, ConsoleActivity.TAB_LOG); break;
                case 4: ConsoleActivity.open(this, vm.id, ConsoleActivity.TAB_MONITOR); break;
                case 5: showFiles(vm); break;
                case 6: confirmDelete(vm); break;
            }
            return true;
        });
        pm.show();
    }

    private void showFiles(VmConfig vm) {
        File dir = VmStore.dir(this, vm.id);
        StringBuilder sb = new StringBuilder(dir.getAbsolutePath()).append("\n\n");
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.getName().endsWith(".sock")) continue;
                sb.append(f.getName()).append("  ").append(Files.humanSize(f.length())).append('\n');
            }
        }
        Ui.message(this, "Dosyalar", sb.toString());
    }

    private void confirmDelete(VmConfig vm) {
        long size = 0;
        File[] fs = VmStore.dir(this, vm.id).listFiles();
        if (fs != null) for (File f : fs) size += f.length();
        new AlertDialog.Builder(this).setTitle(vm.name + " silinsin mi?")
                .setMessage("Uygulama klasöründeki tüm dosyaları (" + Files.humanSize(size)
                        + ", kopyalanmış ISO ve diskler dahil) silinir.")
                .setPositiveButton("Sil", (d, w) -> {
                    VmStore.delete(this, vm.id);
                    refresh();
                })
                .setNegativeButton("İptal", null).show();
    }

    private void chooseTemplate() {
        new AlertDialog.Builder(this).setTitle("Şablon seçin")
                .setItems(VmConfig.TEMPLATE_NAMES, (d, which) -> EditVmActivity.openNew(this, which))
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 1, "Tüm dosyalara erişim izni");
        pm.getMenu().add(0, 2, 2, "Sistem bilgisi / tanılama");
        pm.getMenu().add(0, 3, 3, "QEMU dosyalarını yeniden hazırla");
        pm.getMenu().add(0, 4, 4, "Hakkında");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: requestAllFiles(); break;
                case 2: diagnostics(); break;
                case 3:
                    Files.deleteRecursively(Env.dataDir(this));
                    prepareData();
                    break;
                case 4:
                    Ui.message(this, "Hakkında", "QEMU Android " + BuildConfigInfo.version(this) + "\n\n"
                            + "QEMU 11.1.3 (GPLv2), GLib, pixman, libslirp, EDK2, SeaBIOS.\n"
                            + "Kaynak: github.com/OakHerculesTurkey/Qemu_Android");
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
            return;
        }
        if (Environment.isExternalStorageManager()) {
            Ui.toast(this, "İzin zaten verilmiş. Dosya yollarını /sdcard/... olarak yazabilirsiniz.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void diagnostics() {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Toplanıyor…").show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" (").append(Build.HARDWARE).append(", SoC ")
                    .append(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "?").append(")\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ")
                    .append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("ABI: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
            sb.append("Sayfa boyutu: ").append(Os.sysconf(OsConstants._SC_PAGESIZE)).append('\n');
            sb.append("Çekirdek: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            getSystemService(ActivityManager.class).getMemoryInfo(mi);
            sb.append("RAM: ").append(Files.humanSize(mi.totalMem)).append(" (boş ")
                    .append(Files.humanSize(mi.availMem)).append(")\n");
            sb.append("Depolama boş: ").append(Files.humanSize(getFilesDir().getUsableSpace())).append('\n');
            File kvm = new File("/dev/kvm");
            sb.append("/dev/kvm: ").append(kvm.exists() ? (kvm.canRead() ? "erişilebilir" : "var, izin yok") : "yok")
                    .append('\n');
            sb.append("Uygulama: ").append(BuildConfigInfo.version(this)).append('\n');
            sb.append("Binary klasörü: ").append(Env.binDir(this)).append('\n');
            File[] bins = Env.binDir(this).listFiles();
            if (bins != null) for (File f : bins) sb.append("  ").append(f.getName()).append("  ")
                    .append(Files.humanSize(f.length())).append(f.canExecute() ? "  (x)" : "").append('\n');
            sb.append("QEMU veri: ").append(Env.dataReady(this) ? "hazır" : "hazır DEĞİL").append('\n');
            sb.append('\n').append(run(new File(Env.binDir(this), "libqemu-system-aarch64.so"), "--version"));
            sb.append(run(Env.qemuImg(this), "--version"));
            String s = sb.toString();
            runOnUiThread(() -> {
                wait.dismiss();
                Ui.message(this, "Sistem bilgisi", s);
            });
        }).start();
    }

    private String run(File bin, String arg) {
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), arg).redirectErrorStream(true);
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) > 0) out.append(new String(b, 0, n));
            }
            int code = p.waitFor();
            return "$ " + bin.getName() + " " + arg + "  (çıkış " + code + ")\n" + out + "\n";
        } catch (Exception e) {
            return "$ " + bin.getName() + " " + arg + "\nHATA: " + e + "\n\n";
        }
    }

    /** Version info without generating BuildConfig. */
    static final class BuildConfigInfo {
        static String version(Activity a) {
            try {
                return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }
}
