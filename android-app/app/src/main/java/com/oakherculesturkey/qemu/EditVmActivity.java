package com.oakherculesturkey.qemu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.oakherculesturkey.qemu.core.CommandBuilder;
import com.oakherculesturkey.qemu.core.Shell;
import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Create / edit a VM. The form is built in code to keep it in one place. */
public class EditVmActivity extends Activity {
    static final String EXTRA_ID = "id";
    static final String EXTRA_TEMPLATE = "template";
    static final String EXTRA_COPY_OF = "copy_of";

    private static final int REQ_DISK = 1, REQ_CDROM = 2, REQ_KERNEL = 3, REQ_INITRD = 4;

    private VmConfig cfg;
    private String original;
    private boolean isNew;
    private LinearLayout form;

    private EditText name, machine, cpu, smp, mem, disk, cdrom, kernel, initrd, append, hostfwd,
            tbSize, extra;
    private Spinner arch, firmware, diskIf, bootFrom, display, gpu, input, keymap, network, nic, accel,
            tcgThread;
    private CheckBox usbInput;
    private boolean archInit;

    private static final String[] ARCH_V = {VmConfig.ARCH_AARCH64, VmConfig.ARCH_X86_64};
    private static final String[] ARCH_L = {"ARM64 (aarch64) — hızlı, önerilen", "x86_64 (PC) — yavaş emülasyon"};
    private static final String[] FW_V = {"uefi", "bios", "none"};
    private static final String[] FW_L = {"UEFI (EDK2)", "BIOS (SeaBIOS, sadece x86) / varsayılan", "Yok (doğrudan kernel)"};
    private static final String[] DISKIF_V = {"virtio", "nvme", "ide", "usb"};
    private static final String[] DISKIF_L = {"VirtIO (en hızlı, Linux)", "NVMe", "IDE/SATA (Windows kurulumu, x86)", "USB"};
    private static final String[] BOOT_V = {"disk", "cdrom"};
    private static final String[] BOOT_L = {"Disk", "CD-ROM (kurulum)"};
    private static final String[] DISP_V = {"vnc", "serial"};
    private static final String[] DISP_L = {"Ekran (dahili VNC)", "Sadece seri konsol"};
    private static final String[] GPU_ARM_V = {"virtio-gpu-pci", "ramfb", "none"};
    private static final String[] GPU_ARM_L = {"virtio-gpu (önerilen)", "ramfb (basit framebuffer)", "Yok"};
    private static final String[] GPU_X86_V = {"std", "cirrus-vga", "virtio-vga", "none"};
    private static final String[] GPU_X86_L = {"Standart VGA (uyumlu)",
            "Cirrus (Windows XP/2000/98 sürücüsü dahili)", "virtio-vga", "Yok"};
    private static final String[] KEYMAP_V = {"en-us", "tr"};
    private static final String[] KEYMAP_L = {"İngilizce (ABD)", "Türkçe Q"};
    private static final String[] INPUT_ARM_V = {"xhci"};
    private static final String[] INPUT_ARM_L = {"USB 3 klavye + tablet"};
    private static final String[] INPUT_X86_V = {"xhci", "legacy", "ps2"};
    private static final String[] INPUT_X86_L = {"USB 3 klavye + tablet (Linux, Windows 8+)",
            "PS/2 klavye + USB 1.1 tablet (Windows XP/7, eski sistemler)",
            "Sadece PS/2 klavye ve fare"};
    private static final String[] NET_V = {"user", "none"};
    private static final String[] NET_L = {"NAT (internet, port yönlendirme)", "Ağ yok"};
    private static final String[] NIC_V = {"virtio-net-pci", "e1000", "rtl8139"};
    private static final String[] NIC_L = {"virtio-net (hızlı)", "Intel e1000 (Windows uyumlu)", "Realtek RTL8139"};
    private static final String[] ACCEL_V = {"tcg", "kvm"};
    private static final String[] ACCEL_L = {"TCG (yazılım JIT)", "KVM (/dev/kvm gerekir)"};
    private static final String[] THREAD_V = {"default", "multi", "single"};
    private static final String[] THREAD_L = {"Varsayılan", "Çok iş parçacıklı (MTTCG)", "Tek iş parçacıklı"};

    static void openNew(Context c, int template) {
        c.startActivity(new Intent(c, EditVmActivity.class).putExtra(EXTRA_TEMPLATE, template));
    }

    static void openEdit(Context c, String id) {
        c.startActivity(new Intent(c, EditVmActivity.class).putExtra(EXTRA_ID, id));
    }

    static void openCopy(Context c, String id) {
        c.startActivity(new Intent(c, EditVmActivity.class).putExtra(EXTRA_COPY_OF, id));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (b != null && b.getString("cfg_id") != null) {
            cfg = VmStore.load(this, b.getString("cfg_id"));
        }
        if (cfg == null && in.hasExtra(EXTRA_ID)) {
            cfg = VmStore.load(this, in.getStringExtra(EXTRA_ID));
        }
        if (cfg == null && in.hasExtra(EXTRA_COPY_OF)) {
            VmConfig src = VmStore.load(this, in.getStringExtra(EXTRA_COPY_OF));
            if (src != null) {
                cfg = src.copy();
                cfg.id = VmStore.newId();
                cfg.name = src.name + " (kopya)";
                isNew = true;
            }
        }
        if (cfg == null) {
            cfg = VmConfig.template(in.getIntExtra(EXTRA_TEMPLATE, 0));
            cfg.id = VmStore.newId();
            isNew = true;
        }
        original = cfg.toProperties().toString();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.topBar(this, isNew ? "Yeni sanal makine" : "Ayarlar",
                Ui.button(this, "Komut", v -> previewCommand()),
                Ui.button(this, "Kaydet", v -> save())));
        ScrollView sv = new ScrollView(this);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        form.setPadding(p, 0, p, p * 2);
        sv.addView(form);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        buildForm();
        Ui.applyInsets(root);
        setContentView(root);
        registerBack();
    }

    private void buildForm() {
        section("Genel");
        name = edit("Ad", cfg.name, InputType.TYPE_CLASS_TEXT, null);
        arch = spinner("Mimari", ARCH_V, ARCH_L, cfg.arch);
        machine = edit("Makine (-machine)", cfg.machine, InputType.TYPE_CLASS_TEXT, "virt / q35 / pc");
        note("x86: q35 = modern PC (Windows 7+, Linux). Windows XP/2000/98 için \"pc\" yazın; "
                + "q35'te STOP 0x000000A5 mavi ekranı verir.");
        cpu = edit("İşlemci (-cpu)", cfg.cpu, InputType.TYPE_CLASS_TEXT,
                "ARM: max,pauth-impdef=on · cortex-a76 · x86: max / qemu64");
        int cores = Runtime.getRuntime().availableProcessors();
        smp = edit("Çekirdek sayısı (telefonda " + cores + ")", Integer.toString(cfg.smp),
                InputType.TYPE_CLASS_NUMBER, null);
        mem = edit("Bellek (MB)", Integer.toString(cfg.memMb), InputType.TYPE_CLASS_NUMBER, "2048");
        firmware = spinner("Firmware", FW_V, FW_L, cfg.firmware);

        section("Diskler");
        disk = pathField("Sabit disk (qcow2 / img)", cfg.disk, REQ_DISK, true);
        diskIf = spinner("Disk arayüzü", DISKIF_V, DISKIF_L, cfg.diskIf);
        cdrom = pathField("CD-ROM / ISO", cfg.cdrom, REQ_CDROM, false);
        bootFrom = spinner("Önyükleme", BOOT_V, BOOT_L, cfg.bootFrom);
        note("Boş alan: " + Files.humanSize(getFilesDir().getUsableSpace())
                + ". \"Seç\" dosyayı uygulamanın klasörüne kopyalar. Kopyalamadan kullanmak için "
                + "ana ekrandan \"Tüm dosyalara erişim\" izni verip tam yolu yazabilirsiniz "
                + "(ör. /sdcard/Download/alpine.iso).");

        section("Doğrudan kernel (isteğe bağlı)");
        kernel = pathField("Kernel (Image / bzImage)", cfg.kernel, REQ_KERNEL, false);
        initrd = pathField("Initrd", cfg.initrd, REQ_INITRD, false);
        append = edit("Kernel parametreleri", cfg.append, InputType.TYPE_CLASS_TEXT,
                "console=ttyAMA0 root=/dev/vda");

        section("Ekran ve giriş");
        display = spinner("Görüntü", DISP_V, DISP_L, cfg.display);
        gpu = spinner("Ekran kartı", cfg.isAarch64() ? GPU_ARM_V : GPU_X86_V,
                cfg.isAarch64() ? GPU_ARM_L : GPU_X86_L, cfg.gpu);
        input = spinner("Klavye ve fare", cfg.isAarch64() ? INPUT_ARM_V : INPUT_X86_V,
                cfg.isAarch64() ? INPUT_ARM_L : INPUT_X86_L, cfg.input);
        note("Tablet = mutlak fare: dokunduğunuz yere tıklar. Windows 7 ve öncesinde USB 3 "
                + "sürücüsü yoktur; onlar için PS/2 + USB 1.1 seçin.");
        keymap = spinner("Misafirin klavye düzeni", KEYMAP_V, KEYMAP_L, cfg.keymap);
        note("Misafir işletim sisteminde seçili düzenle aynı olmalı. Ekrandaki PC klavyesi gerçek "
                + "tuş kodu gönderir; bu ayar Android klavyesiyle yazılan metni ve tuş etiketlerini etkiler.");
        usbInput = new CheckBox(this);
        usbInput.setText("Klavye/fare aygıtlarını ekle (ekranlı kullanım için gerekli)");
        usbInput.setChecked(cfg.usbInput);
        form.addView(usbInput);

        section("Ağ");
        network = spinner("Ağ", NET_V, NET_L, cfg.network);
        nic = spinner("Ağ kartı", NIC_V, NIC_L, cfg.nic);
        hostfwd = edit("Port yönlendirme", cfg.hostfwd, InputType.TYPE_CLASS_TEXT,
                "tcp::2222-:22,tcp::8080-:80");
        note("Örnek: tcp::2222-:22 → telefondaki 2222 portu misafirin SSH'ına gider. "
                + "Sadece telefondan erişim için tcp:127.0.0.1:2222-:22 yazın.");

        section("Performans");
        accel = spinner("Hızlandırıcı", ACCEL_V, ACCEL_L, cfg.accel);
        boolean kvm = new File("/dev/kvm").canRead();
        note(kvm ? "/dev/kvm erişilebilir görünüyor." : "Bu cihazda uygulamalar /dev/kvm'ye erişemiyor; TCG kullanın.");
        tcgThread = spinner("TCG iş parçacığı", THREAD_V, THREAD_L, cfg.tcgThread);
        tbSize = edit("TCG çeviri önbelleği (MB, 0 = varsayılan)", Integer.toString(cfg.tbSizeMb),
                InputType.TYPE_CLASS_NUMBER, "0");

        section("Gelişmiş");
        extra = edit("Ek QEMU parametreleri", cfg.extraArgs,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, "-device virtio-rng-pci");
        extra.setSingleLine(false);
        extra.setTypeface(Typeface.MONOSPACE);
        Button preview = Ui.primary(this, "Oluşan QEMU komutunu göster", v -> previewCommand());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        lp.topMargin = Ui.dp(this, 16);
        form.addView(preview, lp);
        Button saveB = Ui.primary(this, "Kaydet", v -> save());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        lp2.topMargin = Ui.dp(this, 8);
        form.addView(saveB, lp2);

        arch.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!archInit) {
                    archInit = true;
                    return;
                }
                VmConfig tmp = readForm();
                tmp.applyArchDefaults();
                machine.setText(tmp.machine);
                cpu.setText(tmp.cpu);
                select(firmware, FW_V, tmp.firmware);
                setOptions(gpu, tmp.isAarch64() ? GPU_ARM_V : GPU_X86_V,
                        tmp.isAarch64() ? GPU_ARM_L : GPU_X86_L, tmp.gpu);
                select(diskIf, DISKIF_V, tmp.diskIf);
                setOptions(input, tmp.isAarch64() ? INPUT_ARM_V : INPUT_X86_V,
                        tmp.isAarch64() ? INPUT_ARM_L : INPUT_X86_L, tmp.input);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // ---- Form helpers --------------------------------------------------------

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.ACCENT);
        t.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 4));
        form.addView(t);
    }

    private void note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setAlpha(0.7f);
        t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        form.addView(t);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        form.addView(t);
        return t;
    }

    private EditText edit(String l, String value, int type, String hint) {
        label(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(value);
        e.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        if (hint != null) e.setHint(hint);
        form.addView(e);
        return e;
    }

    private Spinner spinner(String l, String[] values, String[] labels, String current) {
        label(l);
        Spinner s = new Spinner(this);
        setOptions(s, values, labels, current);
        form.addView(s);
        return s;
    }

    private void setOptions(Spinner s, String[] values, String[] labels, String current) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setTag(values);
        select(s, values, current);
    }

    private static void select(Spinner s, String[] values, String v) {
        int i = Arrays.asList(values).indexOf(v);
        s.setSelection(Math.max(0, i));
    }

    private static String value(Spinner s) {
        String[] v = (String[]) s.getTag();
        int i = s.getSelectedItemPosition();
        return v[Math.max(0, Math.min(i, v.length - 1))];
    }

    private EditText pathField(String l, String value, int req, boolean newDisk) {
        label(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setHint("Boş = yok");
        e.setTextSize(13);
        form.addView(e);
        LinearLayout row = new LinearLayout(this);
        row.addView(Ui.button(this, "Seç…", v -> pick(req)));
        if (newDisk) row.addView(Ui.button(this, "Yeni disk…", v -> createDisk()));
        row.addView(Ui.button(this, "Temizle", v -> e.setText("")));
        form.addView(row);
        return e;
    }

    private VmConfig readForm() {
        VmConfig c = cfg.copy();
        c.name = name.getText().toString().trim();
        c.arch = value(arch);
        c.machine = machine.getText().toString().trim();
        c.cpu = cpu.getText().toString().trim();
        c.smp = parse(smp, 1);
        c.memMb = parse(mem, 1024);
        c.firmware = value(firmware);
        c.disk = disk.getText().toString().trim();
        c.diskIf = value(diskIf);
        c.cdrom = cdrom.getText().toString().trim();
        c.bootFrom = value(bootFrom);
        c.kernel = kernel.getText().toString().trim();
        c.initrd = initrd.getText().toString().trim();
        c.append = append.getText().toString().trim();
        c.display = value(display);
        c.gpu = value(gpu);
        c.usbInput = usbInput.isChecked();
        c.input = value(input);
        c.keymap = value(keymap);
        c.network = value(network);
        c.nic = value(nic);
        c.hostfwd = hostfwd.getText().toString().replace(" ", "");
        c.accel = value(accel);
        c.tcgThread = value(tcgThread);
        c.tbSizeMb = parse(tbSize, 0);
        c.extraArgs = extra.getText().toString().trim();
        return c;
    }

    private static int parse(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    // ---- Actions ---------------------------------------------------------------

    private void previewCommand() {
        VmConfig c = readForm();
        List<String> cmd = CommandBuilder.build(c, VmStore.paths(this, c));
        Ui.message(this, "QEMU komutu", Shell.join(cmd));
    }

    private void save() {
        VmConfig c = readForm();
        if (c.name.isEmpty()) {
            Ui.toast(this, "Bir ad girin");
            return;
        }
        List<String> warn = new ArrayList<>();
        if (!c.disk.isEmpty() && !new File(c.disk).exists()) warn.add("Disk dosyası bulunamadı: " + c.disk);
        if (!c.cdrom.isEmpty() && !new File(c.cdrom).exists()) warn.add("ISO bulunamadı: " + c.cdrom);
        if (!c.kernel.isEmpty() && !new File(c.kernel).exists()) warn.add("Kernel bulunamadı: " + c.kernel);
        if (c.disk.isEmpty() && c.cdrom.isEmpty() && c.kernel.isEmpty())
            warn.add("Disk, ISO veya kernel seçilmedi — VM yalnızca firmware ekranına açılır.");
        try {
            VmStore.save(this, c);
            cfg = c;
            original = c.toProperties().toString();
            if (!warn.isEmpty()) Ui.toast(this, "Kaydedildi. Uyarı: " + String.join("; ", warn));
            else Ui.toast(this, "Kaydedildi");
            finish();
        } catch (Exception e) {
            Ui.message(this, "Kaydedilemedi", e.toString());
        }
    }

    private void createDisk() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        l.setPadding(p, p / 2, p, 0);
        EditText fname = new EditText(this);
        fname.setText("disk.qcow2");
        EditText size = new EditText(this);
        size.setInputType(InputType.TYPE_CLASS_NUMBER);
        size.setText("32");
        TextView t1 = new TextView(this);
        t1.setText("Dosya adı");
        TextView t2 = new TextView(this);
        t2.setText("Boyut (GB) — qcow2 yer ayırmaz, sadece kullanıldıkça büyür");
        l.addView(t1);
        l.addView(fname);
        l.addView(t2);
        l.addView(size);
        new AlertDialog.Builder(this).setTitle("Yeni qcow2 disk").setView(l)
                .setPositiveButton("Oluştur", (d, w) -> {
                    String fn = Files.safeName(fname.getText().toString().trim());
                    if (!fn.endsWith(".qcow2")) fn += ".qcow2";
                    File out = new File(VmStore.dir(this, cfg.id), fn);
                    String gb = size.getText().toString().trim();
                    if (out.exists()) {
                        Ui.toast(this, "Bu adla bir dosya zaten var");
                        return;
                    }
                    runQemuImg(out, gb.isEmpty() ? "32" : gb);
                })
                .setNegativeButton("İptal", null).show();
    }

    private void runQemuImg(File out, String gb) {
        AlertDialog dlg = progressDialog("Disk oluşturuluyor…");
        new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                ProcessBuilder pb = new ProcessBuilder(Env.qemuImg(this).getAbsolutePath(),
                        "create", "-f", "qcow2", out.getAbsolutePath(), gb + "G");
                pb.redirectErrorStream(true);
                pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                Process proc = pb.start();
                StringBuilder sb = new StringBuilder();
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n));
                }
                ok = proc.waitFor() == 0;
                result = sb.toString();
            } catch (Exception e) {
                result = e.toString();
            }
            boolean success = ok;
            String r = result;
            runOnUiThread(() -> {
                dlg.dismiss();
                if (success) {
                    disk.setText(out.getAbsolutePath());
                    Ui.toast(this, "Disk oluşturuldu: " + out.getName());
                } else {
                    Ui.message(this, "qemu-img hatası", r);
                }
            });
        }).start();
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Ui.toast(this, "Dosya seçici açılamadı: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        EditText target = req == REQ_DISK ? disk : req == REQ_CDROM ? cdrom : req == REQ_KERNEL ? kernel : initrd;
        importFile(data.getData(), target);
    }

    private void importFile(Uri uri, EditText target) {
        String display = "file";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) display = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
        }
        File out = new File(VmStore.dir(this, cfg.id), Files.safeName(display));
        if (size > 0 && size > getFilesDir().getUsableSpace()) {
            Ui.message(this, "Yer yok", "Dosya " + Files.humanSize(size) + ", boş alan "
                    + Files.humanSize(getFilesDir().getUsableSpace()));
            return;
        }
        final long total = size;
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(total <= 0);
        TextView info = new TextView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        l.setPadding(p, p, p, 0);
        l.addView(bar);
        l.addView(info);
        final boolean[] cancel = {false};
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Kopyalanıyor: " + display)
                .setView(l).setCancelable(false)
                .setNegativeButton("İptal", (d, w) -> cancel[0] = true).show();
        new Thread(() -> {
            String err = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) throw new java.io.IOException("Dosya açılamadı");
                Files.copy(in, new OutputStream() {
                    @Override
                    public void write(int b) throws java.io.IOException {
                        os.write(b);
                    }

                    @Override
                    public void write(byte[] b, int o, int n) throws java.io.IOException {
                        if (cancel[0]) throw new java.io.IOException("İptal edildi");
                        os.write(b, o, n);
                    }
                }, done -> runOnUiThread(() -> {
                    info.setText(Files.humanSize(done) + (total > 0 ? " / " + Files.humanSize(total) : ""));
                    if (total > 0) bar.setProgress((int) (done * 1000 / total));
                }));
            } catch (Exception e) {
                err = e.getMessage();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            String error = err;
            runOnUiThread(() -> {
                dlg.dismiss();
                if (error == null) {
                    target.setText(out.getAbsolutePath());
                    Ui.toast(this, "Kopyalandı: " + out.getName());
                } else {
                    Ui.toast(this, "Kopyalanamadı: " + error);
                }
            });
        }).start();
    }

    private AlertDialog progressDialog(String title) {
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        int p = Ui.dp(this, 20);
        bar.setPadding(p, p, p, p);
        return new AlertDialog.Builder(this).setTitle(title).setView(bar).setCancelable(false).show();
    }

    // ---- Back handling (predictive back on Android 13+) ------------------------

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::confirmExit);
        }
    }

    /** Only reached below Android 13; newer versions use the OnBackInvokedCallback above. */
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        confirmExit();
    }

    private void confirmExit() {
        if (readForm().toProperties().toString().equals(original)) {
            finish();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Değişiklikler kaydedilsin mi?")
                .setPositiveButton("Kaydet", (d, w) -> save())
                .setNegativeButton("Kaydetme", (d, w) -> finish())
                .setNeutralButton("İptal", null).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (!isNew) out.putString("cfg_id", cfg.id);
    }
}
