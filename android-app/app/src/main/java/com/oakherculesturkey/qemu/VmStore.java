package com.oakherculesturkey.qemu;

import android.content.Context;

import com.oakherculesturkey.qemu.core.CommandBuilder;
import com.oakherculesturkey.qemu.core.VmConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists VM definitions in filesDir/vms/&lt;id&gt;/vm.properties. */
final class VmStore {
    private VmStore() {}

    private static final String CONFIG = "vm.properties";

    static File root(Context c) {
        return new File(c.getFilesDir(), "vms");
    }

    static File dir(Context c, String id) {
        File d = new File(root(c), id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static String newId() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static List<VmConfig> list(Context c) {
        List<VmConfig> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                VmConfig v = load(c, d.getName());
                if (v != null) out.add(v);
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static VmConfig load(Context c, String id) {
        File f = new File(new File(root(c), id), CONFIG);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            VmConfig v = VmConfig.load(in);
            v.id = id;
            return v;
        } catch (IOException e) {
            return null;
        }
    }

    static void save(Context c, VmConfig v) throws IOException {
        if (v.id == null || v.id.isEmpty()) v.id = newId();
        File tmp = new File(dir(c, v.id), CONFIG + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            v.store(o);
        }
        if (!tmp.renameTo(new File(dir(c, v.id), CONFIG))) throw new IOException("rename failed");
    }

    static void delete(Context c, String id) {
        Files.deleteRecursively(new File(root(c), id));
    }

    static CommandBuilder.Paths paths(Context c, VmConfig v) {
        CommandBuilder.Paths p = new CommandBuilder.Paths();
        p.binDir = Env.binDir(c);
        p.dataDir = Env.dataDir(c);
        p.vmDir = dir(c, v.id);
        return p;
    }
}
