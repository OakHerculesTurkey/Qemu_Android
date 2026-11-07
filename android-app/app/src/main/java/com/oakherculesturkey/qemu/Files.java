package com.oakherculesturkey.qemu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Small file helpers (java.nio.file is avoided to keep API 28 support simple). */
final class Files {
    private Files() {}

    interface Progress {
        void onProgress(long done);
    }

    static long copy(InputStream in, OutputStream out, Progress p) throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        long lastReport = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (p != null && total - lastReport > (4 << 20)) {
                lastReport = total;
                p.onProgress(total);
            }
        }
        if (p != null) p.onProgress(total);
        return total;
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Reads at most the last {@code maxBytes} bytes of a file as UTF-8. */
    static String readText(File f, int maxBytes) {
        if (f == null || !f.exists()) return "";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long start = Math.max(0, len - maxBytes);
            byte[] b = new byte[(int) (len - start)];
            r.seek(start);
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static void writeText(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < u.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, u[i]);
    }

    /** Makes a string safe to use as a file name. */
    static String safeName(String n) {
        if (n == null || n.isEmpty()) return "file";
        String s = n.replaceAll("[^A-Za-z0-9._+-]", "_");
        return s.length() > 80 ? s.substring(s.length() - 80) : s;
    }
}
