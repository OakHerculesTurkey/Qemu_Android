package com.oakherculesturkey.qemu;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Connection to one of QEMU's unix sockets inside the app's private dir. */
final class LocalConn implements Closeable {
    final LocalSocket socket;
    final InputStream in;
    final OutputStream out;

    private LocalConn(LocalSocket s) throws IOException {
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    /** Connects, retrying while QEMU is still starting up. */
    static LocalConn connect(File path, long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (true) {
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(path.getAbsolutePath(),
                        LocalSocketAddress.Namespace.FILESYSTEM));
                return new LocalConn(s);
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            if (System.currentTimeMillis() > end) throw last;
            try {
                Thread.sleep(150);
            } catch (InterruptedException ie) {
                throw new IOException("interrupted");
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            socket.shutdownInput();
        } catch (IOException ignored) {
        }
        socket.close();
    }
}
