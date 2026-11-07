import com.oakherculesturkey.qemu.core.CommandBuilder;
import com.oakherculesturkey.qemu.core.KeyLayouts;
import com.oakherculesturkey.qemu.core.Modifiers;
import com.oakherculesturkey.qemu.core.QmpClient;
import com.oakherculesturkey.qemu.core.RfbClient;
import com.oakherculesturkey.qemu.core.Shell;
import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host-side test of the app's pure-Java core against the real QEMU Android
 * build (x86_64 ABI, started through bionic's linker64).
 *
 * args: <bionic dir> <qemu bin dir> <qemu data dir> <work dir> <baremetal elf>
 */
public class CoreTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + what);
        if (!ok) failures++;
    }

    /** Socket streams that allow a concurrent reader and writer. */
    static final class Conn implements Closeable {
        final SocketChannel ch;
        final InputStream in;
        final OutputStream out;

        Conn(File path, long timeoutMs) throws Exception {
            long end = System.currentTimeMillis() + timeoutMs;
            SocketChannel c = null;
            while (true) {
                try {
                    c = SocketChannel.open(StandardProtocolFamily.UNIX);
                    c.connect(UnixDomainSocketAddress.of(path.toPath()));
                    break;
                } catch (IOException e) {
                    if (c != null) c.close();
                    if (System.currentTimeMillis() > end) throw e;
                    Thread.sleep(100);
                }
            }
            ch = c;
            in = new InputStream() {
                public int read() throws IOException {
                    byte[] b = new byte[1];
                    return read(b, 0, 1) < 0 ? -1 : (b[0] & 0xff);
                }
                public int read(byte[] b, int o, int l) throws IOException {
                    return ch.read(ByteBuffer.wrap(b, o, l));
                }
            };
            out = new OutputStream() {
                public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                public void write(byte[] b, int o, int l) throws IOException {
                    ByteBuffer bb = ByteBuffer.wrap(b, o, l);
                    while (bb.hasRemaining()) ch.write(bb);
                }
            };
        }

        public void close() throws IOException { ch.close(); }
    }

    static Process start(VmConfig c, CommandBuilder.Paths p, File bionic, File log) throws IOException {
        List<String> cmd = CommandBuilder.build(c, p);
        // binDir holds the real binaries under their Android jniLibs names
        List<String> full = new ArrayList<>();
        full.add(new File(bionic, "linker64").getAbsolutePath());
        full.addAll(cmd);
        System.out.println("  $ " + Shell.join(cmd));
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.environment().put("LD_LIBRARY_PATH", bionic.getAbsolutePath());
        pb.environment().put("TMPDIR", p.vmDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);
        return pb.start();
    }

    static String qmp(CommandBuilder.Paths p, String json) throws Exception {
        try (Conn c = new Conn(p.qmpSocket(), 10000)) {
            return QmpClient.execute(c.in, c.out, json);
        }
    }

    /** Raw framebuffer (int w, int h, ARGB ints) for the app's screenshot test. */
    static void saveFramebuffer(RfbClient rfb, java.lang.reflect.Field f, File out) throws Exception {
        int[] fb = (int[]) f.get(rfb);
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(out)))) {
            o.writeInt(rfb.getWidth());
            o.writeInt(rfb.getHeight());
            for (int px : fb) o.writeInt(px);
        }
    }

    static void testVnc(String label, VmConfig cfg, File bionic, File bin, File data, File work)
            throws Exception {
        System.out.println("== " + label);
        CommandBuilder.Paths p = new CommandBuilder.Paths();
        p.binDir = bin;
        p.dataDir = data;
        p.vmDir = new File(work, label);
        p.vmDir.mkdirs();
        Process proc = start(cfg, p, bionic, new File(p.vmDir, "qemu.log"));
        try {
            final int[] size = new int[2];
            final long[] lit = new long[1];
            final CountDownLatch drawn = new CountDownLatch(1);
            final Exception[] err = new Exception[1];
            Conn conn = new Conn(p.vncSocket(), 15000);
            RfbClient rfb = new RfbClient(conn.in, conn.out, conn, new RfbClient.Listener() {
                public void onConnected(String n, int w, int h) { size[0] = w; size[1] = h; }
                public void onResize(int w, int h) { size[0] = w; size[1] = h; }
                public void onUpdate(int[] fb, int fw, int x, int y, int w, int h) {}
                public void onFrameComplete() {}
                public void onBell() {}
                public void onDisconnected(Exception e) { err[0] = e; }
            });
            Thread t = new Thread(rfb::run);
            t.start();
            // Poll the framebuffer until something non-black shows up.
            java.lang.reflect.Field f = RfbClient.class.getDeclaredField("fb");
            f.setAccessible(true);
            long end = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < end) {
                int[] fb = (int[]) f.get(rfb);
                long n = 0;
                for (int px : fb) if ((px & 0xFFFFFF) != 0) n++;
                lit[0] = n;
                if (n > 500) { drawn.countDown(); break; }
                Thread.sleep(250);
            }
            check(size[0] > 0 && size[1] > 0, "VNC handshake, framebuffer " + size[0] + "x" + size[1]);
            check(drawn.getCount() == 0, "guest drew on screen (" + lit[0] + " non-black px)");
            // input path: must not break the connection
            rfb.sendPointer(10, 10, 0);
            rfb.tapKey(com.oakherculesturkey.qemu.core.Keysyms.ESCAPE);
            check(rfb.hasExtendedKeys(), "server accepted QEMU extended key events (scancodes)");
            if (!cfg.isAarch64()) {
                // physical Esc (scancode 0x01) at SeaBIOS' "Press ESC for boot menu"
                File bios = new File(p.vmDir, "bios.log");
                long until = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < until) {
                    rfb.sendScancode(com.oakherculesturkey.qemu.core.Keysyms.ESCAPE, 0x01, true);
                    Thread.sleep(60);
                    rfb.sendScancode(com.oakherculesturkey.qemu.core.Keysyms.ESCAPE, 0x01, false);
                    Thread.sleep(400);
                    if (bios.exists() && new String(Files.readAllBytes(bios.toPath()))
                            .contains("Select boot device")) break;
                }
                String bl = bios.exists() ? new String(Files.readAllBytes(bios.toPath())) : "";
                check(bl.contains("Select boot device"),
                        "scancode Esc reached the guest (SeaBIOS boot menu opened)");
            }
            Thread.sleep(300);
            check(err[0] == null && !rfb.isClosed(), "VNC input accepted, connection alive");
            saveFramebuffer(rfb, f, new File(work, label + ".fb"));

            String st = qmp(p, QmpClient.command("query-status"));
            check(st.contains("running"), "QMP query-status: " + QmpClient.describe(st));
            String ver = qmp(p, QmpClient.hmp("info version"));
            check(QmpClient.describe(ver).trim().startsWith("11."), "QMP HMP 'info version': " + QmpClient.describe(ver).trim());
            String stop = qmp(p, QmpClient.command("stop"));
            String st2 = qmp(p, QmpClient.command("query-status"));
            check(st2.contains("paused"), "QMP stop -> paused");
            qmp(p, QmpClient.command("cont"));
            rfb.close();
            t.join(5000);
            qmp(p, QmpClient.command("quit"));
            check(proc.waitFor(15, TimeUnit.SECONDS) && proc.exitValue() == 0, "QMP quit -> clean exit");
        } finally {
            proc.destroyForcibly();
            System.out.println("  log: " + new String(Files.readAllBytes(new File(p.vmDir, "qemu.log").toPath())).trim().replace("\n", "\n       "));
        }
    }

    public static void main(String[] args) throws Exception {
        File bionic = new File(args[0]), bin = new File(args[1]), data = new File(args[2]);
        File work = new File(args[3]), elf = new File(args[4]);

        // 1) aarch64, UEFI + virtio-gpu over VNC (TianoCore logo)
        VmConfig a = VmConfig.template(0);
        a.smp = 2;
        a.memMb = 512;
        a.tcgThread = "multi";
        testVnc("aarch64-uefi-vnc", a, bionic, bin, data, work);

        // 2) x86_64 Windows templates (7: q35 + UHCI tablet; XP: pc + Cirrus +
        //    RTL8139), SeaBIOS over VNC, with an IDE qcow2 disk so the block
        //    layer (coroutines) is exercised
        for (int tpl : new int[]{3, 4}) {
            String label = "x86_64-" + (tpl == 3 ? "win7" : "winxp");
            File xdir = new File(work, label);
            xdir.mkdirs();
            File disk = new File(xdir, "disk.qcow2");
            ProcessBuilder ipb = new ProcessBuilder(new File(bionic, "linker64").getAbsolutePath(),
                    new File(bin, "libqemu-img.so").getAbsolutePath(), "create", "-f", "qcow2",
                    disk.getAbsolutePath(), "1G").redirectErrorStream(true);
            ipb.environment().put("LD_LIBRARY_PATH", bionic.getAbsolutePath());
            Process img = ipb.start();
            img.getInputStream().readAllBytes();
            check(img.waitFor() == 0 && disk.exists(), "qemu-img create (" + label + ")");
            VmConfig x = VmConfig.template(tpl);
            if (tpl == 3) x.keymap = "tr"; // exercises "-k tr"
            x.memMb = 256;
            x.disk = disk.getAbsolutePath();
            File bios = new File(xdir, "bios.log");
            x.extraArgs = "-chardev file,id=dbg,path=" + bios.getAbsolutePath()
                    + " -device isa-debugcon,iobase=0x402,chardev=dbg -boot menu=on,splash-time=15000";
            testVnc(label, x, bionic, bin, data, work);
            String bl = bios.exists() ? new String(Files.readAllBytes(bios.toPath())) : "";
            check(bl.contains("QEMU HARDDISK"),
                    label + ": SeaBIOS found the IDE disk");
        }

        // 3) serial console only: bare-metal aarch64 kernel writes to the UART
        System.out.println("== aarch64-serial");
        VmConfig s = VmConfig.template(1);
        s.kernel = elf.getAbsolutePath();
        s.firmware = "none";
        s.cpu = "cortex-a57";
        s.smp = 1;
        s.memMb = 128;
        s.network = "none";
        CommandBuilder.Paths p = new CommandBuilder.Paths();
        p.binDir = bin;
        p.dataDir = data;
        p.vmDir = new File(work, "serial");
        p.vmDir.mkdirs();
        Process proc = start(s, p, bionic, new File(p.vmDir, "qemu.log"));
        try (Conn c = new Conn(p.serialSocket(), 10000)) {
            // Output may precede the connection; it always lands in the log file.
            String text = "";
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end && !text.contains("Hello")) {
                Thread.sleep(200);
                if (p.serialLog().exists()) text = new String(Files.readAllBytes(p.serialLog().toPath()));
            }
            check(text.contains("Hello from an aarch64 guest"), "serial log: " + text.trim());
            qmp(p, QmpClient.command("quit"));
            check(proc.waitFor(15, TimeUnit.SECONDS), "serial VM quit");
        } finally {
            proc.destroyForcibly();
        }

        // 4) keyboard layouts and modifier logic
        for (boolean tr : new boolean[]{false, true}) {
            java.util.List<java.util.List<KeyLayouts.Key>> rows = KeyLayouts.full(tr);
            boolean ok = rows.size() == 6;
            for (java.util.List<KeyLayouts.Key> r : rows)
                for (KeyLayouts.Key k : r)
                    if (k.kind == KeyLayouts.KIND_KEY && k.qnum == 0) ok = false;
            check(ok, (tr ? "Turkish" : "US") + " layout: 6 rows, every key has a scancode");
        }
        check(KeyLayouts.full(true).get(2).get(8).label.equals("ı")
                && KeyLayouts.full(true).get(2).get(8).qnum == 0x17, "Turkish ı sits on the physical I key");
        final java.util.List<String> ev = new java.util.ArrayList<>();
        Modifiers mods = new Modifiers();
        mods.setSink((sym, q, down) -> ev.add(Integer.toHexString(q) + (down ? "+" : "-")));
        mods.press(Modifiers.CTRL); mods.release(Modifiers.CTRL);       // tap -> one-shot
        mods.keyDown(); mods.keyUp();                                    // next key consumes it
        check(ev.toString().equals("[1d+, 1d-]") && mods.state(Modifiers.CTRL) == Modifiers.OFF,
                "modifier tap = one-shot " + ev);
        ev.clear();
        mods.press(Modifiers.ALT); mods.keyDown(); mods.keyUp(); mods.release(Modifiers.ALT); // chord
        check(ev.toString().equals("[38+, 38-]"), "modifier hold = chord " + ev);
        ev.clear();
        mods.press(Modifiers.SHIFT); mods.release(Modifiers.SHIFT);
        mods.press(Modifiers.SHIFT); mods.release(Modifiers.SHIFT);     // double tap -> locked
        mods.keyDown(); mods.keyUp(); mods.keyDown(); mods.keyUp();
        boolean locked = mods.state(Modifiers.SHIFT) == Modifiers.LOCKED;
        mods.press(Modifiers.SHIFT); mods.release(Modifiers.SHIFT);     // third tap unlocks
        check(locked && ev.toString().equals("[2a+, 2a-]") && mods.state(Modifiers.SHIFT) == Modifiers.OFF,
                "modifier double tap = lock " + ev);

        // 5) pure helpers
        check(Shell.split("-device 'virtio-rng-pci,id=r0' -object \"a b\"").size() == 4, "Shell.split quoting");
        check(QmpClient.describe("{\"return\": \"a\\nb\"}").equals("a\nb"), "QMP string unescape");

        System.out.println(failures == 0 ? "ALL CORE TESTS PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
