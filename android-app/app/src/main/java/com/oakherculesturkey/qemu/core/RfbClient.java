package com.oakherculesturkey.qemu.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small RFB (VNC) 3.3/3.8 client for QEMU's built-in VNC server.
 * Supports Raw, CopyRect and DesktopSize; the transport is a local socket so
 * bandwidth is not a concern. Pure Java so it can be tested on the host.
 *
 * The framebuffer is kept as ARGB ints (0xFFRRGGBB), ready for
 * android.graphics.Bitmap#setPixels.
 */
public final class RfbClient implements Closeable {

    public interface Listener {
        void onConnected(String desktopName, int width, int height);
        /** Framebuffer was (re)allocated; called before any update of the new size. */
        void onResize(int width, int height);
        /** A rectangle of {@code fb} (stride = width) changed. Called on the client thread. */
        void onUpdate(int[] fb, int fbWidth, int x, int y, int w, int h);
        /** All rectangles of one FramebufferUpdate have been applied. */
        void onFrameComplete();
        void onBell();
        void onDisconnected(Exception cause);
    }

    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_RIGHT = 4;
    public static final int WHEEL_UP = 8;
    public static final int WHEEL_DOWN = 16;

    private static final int ENC_RAW = 0;
    private static final int ENC_COPYRECT = 1;
    private static final int ENC_DESKTOP_SIZE = -223;
    /** QEMU extension: client may send raw PC scancodes (layout independent). */
    private static final int ENC_QEMU_EXT_KEY = -258;

    private final DataInputStream in;
    private final DataOutputStream out;
    private final Closeable transport;
    private final Listener listener;
    private volatile boolean closed;
    private volatile boolean extendedKeys;

    private int width;
    private int height;
    private int[] fb = new int[0];
    private byte[] row = new byte[0];

    public RfbClient(InputStream in, OutputStream out, Closeable transport, Listener listener) {
        this.in = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        this.out = new DataOutputStream(new BufferedOutputStream(out, 4096));
        this.transport = transport;
        this.listener = listener;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /** Performs the handshake and processes server messages until closed. */
    public void run() {
        Exception cause = null;
        try {
            handshake();
            requestUpdate(false);
            while (!closed) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0: readFramebufferUpdate(); break;
                    case 1: readColourMap(); break;
                    case 2: listener.onBell(); break;
                    case 3: readCutText(); break;
                    default: throw new IOException("Unknown RFB message type " + type);
                }
            }
        } catch (Exception e) {
            if (!closed) cause = e;
        } finally {
            closeQuietly();
            listener.onDisconnected(cause);
        }
    }

    private void handshake() throws IOException {
        byte[] ver = new byte[12];
        in.readFully(ver);
        String v = new String(ver, StandardCharsets.US_ASCII);
        if (!v.startsWith("RFB ")) throw new IOException("Not an RFB server: " + v.trim());
        int minor = Integer.parseInt(v.substring(8, 11));
        boolean v38 = minor >= 7;
        synchronized (out) {
            out.write((v38 ? "RFB 003.008\n" : "RFB 003.003\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        if (v38) {
            int n = in.readUnsignedByte();
            if (n == 0) throw new IOException("Server refused: " + readString());
            boolean none = false;
            for (int i = 0; i < n; i++) if (in.readUnsignedByte() == 1) none = true;
            if (!none) throw new IOException("VNC server requires authentication");
            synchronized (out) {
                out.writeByte(1);
                out.flush();
            }
            int result = in.readInt();
            if (result != 0) throw new IOException("VNC security failed: " + readString());
        } else {
            int sec = in.readInt();
            if (sec == 0) throw new IOException("Server refused: " + readString());
            if (sec != 1) throw new IOException("VNC server requires authentication");
        }
        synchronized (out) {
            out.writeByte(1); // ClientInit: shared
            out.flush();
        }
        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        in.skipBytes(16); // server pixel format, we set our own below
        String name = readString();
        resize(w, h);
        listener.onConnected(name, w, h);

        synchronized (out) {
            // SetPixelFormat: 32bpp, depth 24, little endian, true colour,
            // R<<16 G<<8 B -> reading 4 LE bytes yields 0x00RRGGBB.
            out.writeByte(0);
            out.write(new byte[3]);
            out.writeByte(32);
            out.writeByte(24);
            out.writeByte(0);
            out.writeByte(1);
            out.writeShort(255);
            out.writeShort(255);
            out.writeShort(255);
            out.writeByte(16);
            out.writeByte(8);
            out.writeByte(0);
            out.write(new byte[3]);
            // SetEncodings
            int[] enc = {ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE, ENC_QEMU_EXT_KEY};
            out.writeByte(2);
            out.writeByte(0);
            out.writeShort(enc.length);
            for (int e : enc) out.writeInt(e);
            out.flush();
        }
    }

    private String readString() throws IOException {
        int len = in.readInt();
        if (len < 0 || len > (1 << 20)) throw new IOException("Bad string length " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private void resize(int w, int h) {
        width = w;
        height = h;
        fb = new int[Math.max(1, w * h)];
        java.util.Arrays.fill(fb, 0xFF000000);
        listener.onResize(w, h);
    }

    private void readFramebufferUpdate() throws IOException {
        in.readUnsignedByte();
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int enc = in.readInt();
            switch (enc) {
                case ENC_RAW: readRaw(x, y, w, h); break;
                case ENC_COPYRECT: readCopyRect(x, y, w, h); break;
                case ENC_DESKTOP_SIZE: resize(w, h); break;
                case ENC_QEMU_EXT_KEY: extendedKeys = true; break;
                default: throw new IOException("Unsupported encoding " + enc);
            }
        }
        listener.onFrameComplete();
        requestUpdate(true);
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        int bytes = w * 4;
        if (row.length < bytes) row = new byte[bytes];
        boolean inside = x + w <= width && y + h <= height;
        for (int j = 0; j < h; j++) {
            in.readFully(row, 0, bytes);
            if (!inside) continue;
            int o = (y + j) * width + x;
            for (int i = 0, b = 0; i < w; i++, b += 4) {
                fb[o + i] = 0xFF000000
                        | ((row[b + 2] & 0xFF) << 16)
                        | ((row[b + 1] & 0xFF) << 8)
                        | (row[b] & 0xFF);
            }
        }
        if (inside && w > 0 && h > 0) listener.onUpdate(fb, width, x, y, w, h);
    }

    private void readCopyRect(int x, int y, int w, int h) throws IOException {
        int sx = in.readUnsignedShort();
        int sy = in.readUnsignedShort();
        if (x + w > width || y + h > height || sx + w > width || sy + h > height) return;
        if (sy < y) {
            for (int j = h - 1; j >= 0; j--)
                System.arraycopy(fb, (sy + j) * width + sx, fb, (y + j) * width + x, w);
        } else {
            for (int j = 0; j < h; j++)
                System.arraycopy(fb, (sy + j) * width + sx, fb, (y + j) * width + x, w);
        }
        listener.onUpdate(fb, width, x, y, w, h);
    }

    private void readColourMap() throws IOException {
        in.readUnsignedByte();
        in.readUnsignedShort();
        int n = in.readUnsignedShort();
        in.skipBytes(n * 6);
    }

    private void readCutText() throws IOException {
        in.skipBytes(3);
        int len = in.readInt();
        while (len > 0) len -= in.skipBytes(len);
    }

    // ---- Client -> server --------------------------------------------------

    public void requestUpdate(boolean incremental) throws IOException {
        synchronized (out) {
            out.writeByte(3);
            out.writeByte(incremental ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(width);
            out.writeShort(height);
            out.flush();
        }
    }

    public void sendKey(int keysym, boolean down) {
        if (closed) return;
        try {
            synchronized (out) {
                out.writeByte(4);
                out.writeByte(down ? 1 : 0);
                out.writeShort(0);
                out.writeInt(keysym);
                out.flush();
            }
        } catch (IOException ignored) {
            // the reader thread notices the broken connection
        }
    }

    /** Whether the server accepted QEMU extended key events (raw scancodes). */
    public boolean hasExtendedKeys() {
        return extendedKeys;
    }

    /**
     * Key event by physical PC scancode ("qnum": XT set 1, extended = 0x80|code)
     * when the server supports it, otherwise by keysym.
     */
    public void sendScancode(int keysym, int qnum, boolean down) {
        if (closed) return;
        if (!extendedKeys || qnum == 0) {
            sendKey(keysym, down);
            return;
        }
        try {
            synchronized (out) {
                out.writeByte(255);       // QEMU client message
                out.writeByte(0);         // extended key event
                out.writeShort(down ? 1 : 0);
                out.writeInt(keysym);
                out.writeInt(qnum);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    /** Press and release, wrapped in the given modifier keysyms. */
    public void tapKey(int keysym, int... modifiers) {
        for (int m : modifiers) sendKey(m, true);
        sendKey(keysym, true);
        sendKey(keysym, false);
        for (int i = modifiers.length - 1; i >= 0; i--) sendKey(modifiers[i], false);
    }

    public void sendPointer(int x, int y, int buttons) {
        if (closed) return;
        x = Math.max(0, Math.min(x, Math.max(0, width - 1)));
        y = Math.max(0, Math.min(y, Math.max(0, height - 1)));
        try {
            synchronized (out) {
                out.writeByte(5);
                out.writeByte(buttons);
                out.writeShort(x);
                out.writeShort(y);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            transport.close();
        } catch (IOException ignored) {
        }
    }
}
