package com.oakherculesturkey.qemu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.oakherculesturkey.qemu.core.CommandBuilder;
import com.oakherculesturkey.qemu.core.QmpClient;
import com.oakherculesturkey.qemu.core.Shell;
import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service owning the QEMU processes. QEMU runs as a child process
 * (the binary is executed straight from nativeLibraryDir); its output goes to
 * an in-memory log and to vms/&lt;id&gt;/qemu.log.
 */
public class VmService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_STOP_ALL = "stop_all";
    static final String EXTRA_ID = "id";

    private static final String CHANNEL = "vms";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_LOG = 256 * 1024;

    static final class Running {
        final VmConfig config;
        final CommandBuilder.Paths paths;
        volatile Process process;
        final long startedAt = System.currentTimeMillis();

        Running(VmConfig c, CommandBuilder.Paths p) {
            config = c;
            paths = p;
        }
    }

    private static final Map<String, Running> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, StringBuilder> LOGS = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PowerManager.WakeLock wakeLock;

    // ---- Static API used by the activities --------------------------------

    static void start(Context c, String id) {
        Intent i = new Intent(c, VmService.class).setAction(ACTION_START).putExtra(EXTRA_ID, id);
        c.startForegroundService(i);
    }

    static boolean isRunning(String id) {
        return RUNNING.containsKey(id);
    }

    static Running get(String id) {
        return RUNNING.get(id);
    }

    static int runningCount() {
        return RUNNING.size();
    }

    static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) r.run();
        });
    }

    /** Current in-memory log, or the last log file of the VM. */
    static String log(Context c, String id) {
        StringBuilder sb = LOGS.get(id);
        if (sb != null) {
            synchronized (sb) {
                return sb.toString();
            }
        }
        return Files.readText(new File(VmStore.dir(c, id), "qemu.log"), MAX_LOG);
    }

    private static void appendLog(String id, String s) {
        StringBuilder sb = LOGS.get(id);
        if (sb == null) return;
        synchronized (sb) {
            sb.append(s);
            if (sb.length() > MAX_LOG) sb.delete(0, sb.length() - MAX_LOG / 2);
        }
    }

    /** Runs one QMP command against a running VM (blocking; call off the UI thread). */
    static String qmp(String id, String json) throws IOException {
        Running r = RUNNING.get(id);
        if (r == null) throw new IOException("VM çalışmıyor");
        try (LocalConn c = LocalConn.connect(r.paths.qmpSocket(), 3000)) {
            return QmpClient.execute(c.in, c.out, json);
        }
    }

    /** Graceful quit through QMP, killing the process if it does not exit. */
    static void stop(String id) {
        new Thread(() -> {
            Running r = RUNNING.get(id);
            if (r == null) return;
            try {
                qmp(id, QmpClient.command("quit"));
            } catch (IOException ignored) {
            }
            Process p = r.process;
            if (p == null) return;
            for (int i = 0; i < 30 && isAlive(p); i++) sleep(100);
            if (isAlive(p)) p.destroy();
            for (int i = 0; i < 20 && isAlive(p); i++) sleep(100);
            if (isAlive(p)) p.destroyForcibly();
        }, "vm-stop").start();
    }

    private static boolean isAlive(Process p) {
        return p.isAlive();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---- Service ------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Çalışan sanal makineler",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QemuAndroid:vm");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must be called promptly after startForegroundService().
        goForeground();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_ALL.equals(action)) {
            for (String id : RUNNING.keySet()) stop(id);
        } else if (ACTION_START.equals(action)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (id != null && !RUNNING.containsKey(id)) launch(id);
        }
        if (RUNNING.isEmpty() && !ACTION_START.equals(action)) shutdownIfIdle();
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        int n = RUNNING.size();
        StringBuilder names = new StringBuilder();
        for (Running r : RUNNING.values()) {
            if (names.length() > 0) names.append(", ");
            names.append(r.config.name);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopAll = PendingIntent.getService(this, 1,
                new Intent(this, VmService.class).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vm)
                .setContentTitle(n == 0 ? "QEMU başlatılıyor…" : n + " sanal makine çalışıyor")
                .setContentText(names.length() > 0 ? names.toString() : "Hazırlanıyor")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Hepsini kapat", stopAll).build())
                .build();
    }

    private void updateNotification() {
        if (RUNNING.isEmpty()) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private void shutdownIfIdle() {
        if (!RUNNING.isEmpty()) return;
        if (wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void launch(String id) {
        VmConfig cfg = VmStore.load(this, id);
        if (cfg == null) {
            shutdownIfIdle();
            return;
        }
        CommandBuilder.Paths paths = VmStore.paths(this, cfg);
        Running r = new Running(cfg, paths);
        RUNNING.put(id, r);
        LOGS.put(id, new StringBuilder());
        acquireWakeLock();
        updateNotification();
        changed();
        new Thread(() -> runVm(r), "vm-" + id).start();
    }

    /** Held for as long as any VM runs (VMs run indefinitely, so no timeout). */
    @android.annotation.SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        wakeLock.acquire();
    }

    private void runVm(Running r) {
        String id = r.config.id;
        File logFile = r.paths.qemuLog();
        OutputStream logOut = null;
        int code = -1;
        try {
            logOut = new FileOutputStream(logFile);
            Env.ensureData(this);
            for (File s : new File[]{r.paths.vncSocket(), r.paths.qmpSocket(), r.paths.serialSocket()}) {
                //noinspection ResultOfMethodCallIgnored
                s.delete();
            }
            List<String> cmd = CommandBuilder.build(r.config, r.paths);
            String header = "# " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                    + "  " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + "), " + Build.SUPPORTED_ABIS[0] + "\n$ "
                    + Shell.join(cmd) + "\n\n";
            write(id, logOut, header);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(r.paths.vmDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("HOME", getFilesDir().getAbsolutePath());
            env.put("SLIRP_RESOLV_CONF", Env.writeResolvConf(this).getAbsolutePath());
            Process p = pb.start();
            r.process = p;
            changed();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                    write(id, logOut, s);
                }
            }
            code = p.waitFor();
            write(id, logOut, "\n# QEMU çıktı, kod " + code + "\n");
            if (code > 128 && code < 160) {
                write(id, logOut, "# Sinyal ile sonlandı: " + signalName(code - 128)
                        + ". Yukarıda \"QEMU-ANDROID CRASH\" raporu varsa lütfen log'u paylaşın.\n");
            }
            if (code == 137) {
                write(id, logOut, "# QEMU Android tarafından öldürüldü (SIGKILL). Olası nedenler: bellek "
                        + "yetersizliği veya Android'in alt süreç kısıtlaması (phantom process killer). "
                        + "Geliştirici seçenekleri → \"Alt süreç kısıtlamalarını devre dışı bırak\" "
                        + "seçeneğini açın veya VM belleğini azaltın.\n");
            }
        } catch (Exception e) {
            write(id, logOut, "\n# HATA: " + e + "\n");
        } finally {
            if (logOut != null) {
                try {
                    logOut.close();
                } catch (IOException ignored) {
                }
            }
            RUNNING.remove(id);
            final int exit = code;
            MAIN.post(() -> {
                updateNotification();
                shutdownIfIdle();
                if (exit != 0) Ui.toast(this, r.config.name + ": QEMU hata ile kapandı (" + exit + "). Log'a bakın.");
            });
            changed();
        }
    }

    private static String signalName(int sig) {
        switch (sig) {
            case 4: return "SIGILL (geçersiz komut)";
            case 6: return "SIGABRT (QEMU kendini durdurdu)";
            case 7: return "SIGBUS (bellek erişim hatası)";
            case 9: return "SIGKILL (Android tarafından öldürüldü)";
            case 11: return "SIGSEGV (bellek hatası)";
            case 31: return "SIGSYS (engellenen sistem çağrısı)";
            default: return "sinyal " + sig;
        }
    }

    private static void write(String id, OutputStream f, String s) {
        appendLog(id, s);
        if (f != null) {
            try {
                f.write(s.getBytes(StandardCharsets.UTF_8));
                f.flush();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
