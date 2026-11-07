package com.oakherculesturkey.qemu.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * On-screen PC keyboard layouts. Keys carry the physical PC scancode (QEMU
 * "qnum": XT set 1, extended keys = 0x80 | code), so the guest OS keyboard
 * layout decides which character a key produces. Labels exist for US and
 * Turkish Q; the keysym is only a fallback for servers without the QEMU
 * extended key event.
 */
public final class KeyLayouts {
    private KeyLayouts() {}

    public static final int KIND_KEY = 0;
    public static final int KIND_MOD = 1;
    public static final int KIND_COMBO = 2;
    public static final int KIND_MOUSE = 3;
    public static final int KIND_ACTION = 4;

    /** Actions handled by the host UI rather than sent to the guest. */
    public static final int ACTION_ANDROID_IME = 1;
    public static final int ACTION_PAGE_MAIN = 2;
    public static final int ACTION_PAGE_NAV = 3;
    public static final int ACTION_HIDE = 4;

    /** Mouse "keys": button masks, or wheel. */
    public static final int MOUSE_WHEEL_UP = 100;
    public static final int MOUSE_WHEEL_DOWN = 101;

    public static final class Key {
        public final String label;
        public final String shiftLabel;
        public final int keysym;
        public final int qnum;
        public final float width;
        public final int kind;
        /** KIND_MOD: Modifiers index; KIND_MOUSE: button mask / wheel; KIND_ACTION: action. */
        public final int arg;
        /** KIND_COMBO: modifiers held, then {@link #qnum}/{@link #keysym} tapped. */
        public final int[] comboMods;

        Key(String label, String shiftLabel, int keysym, int qnum, float width, int kind,
            int arg, int[] comboMods) {
            this.label = label;
            this.shiftLabel = shiftLabel;
            this.keysym = keysym;
            this.qnum = qnum;
            this.width = width;
            this.kind = kind;
            this.arg = arg;
            this.comboMods = comboMods;
        }

        public boolean isLetter() {
            return label.length() == 1 && Character.isLetter(label.charAt(0));
        }
    }

    // ---- builders ----------------------------------------------------------

    static Key k(String label, String shift, int keysym, int qnum, float w) {
        return new Key(label, shift, keysym, qnum, w, KIND_KEY, 0, null);
    }

    static Key k(String label, int keysym, int qnum, float w) {
        return k(label, null, keysym, qnum, w);
    }

    static Key mod(String label, int modIndex, float w) {
        return new Key(label, null, Modifiers.SYMS[modIndex], Modifiers.QNUMS[modIndex], w,
                KIND_MOD, modIndex, null);
    }

    static Key combo(String label, int keysym, int qnum, float w, int... mods) {
        return new Key(label, null, keysym, qnum, w, KIND_COMBO, 0, mods);
    }

    static Key mouse(String label, int arg, float w) {
        return new Key(label, null, 0, 0, w, KIND_MOUSE, arg, null);
    }

    static Key action(String label, int action, float w) {
        return new Key(label, null, 0, 0, w, KIND_ACTION, action, null);
    }

    // Physical scancodes of the alphanumeric block, by row (US labels).
    private static final String US_NUM = "`1234567890-=";
    private static final String US_NUM_SHIFT = "~!@#$%^&*()_+";
    private static final int[] NUM_Q = {0x29, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d};
    private static final String US_Q = "qwertyuiop[]";
    private static final String US_Q_SHIFT = "QWERTYUIOP{}";
    private static final int[] Q_Q = {0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b};
    private static final String US_A = "asdfghjkl;'";
    private static final String US_A_SHIFT = "ASDFGHJKL:\"";
    private static final int[] A_Q = {0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28};
    private static final String US_Z = "zxcvbnm,./";
    private static final String US_Z_SHIFT = "ZXCVBNM<>?";
    private static final int[] Z_Q = {0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35};

    // Turkish Q labels for the same physical keys
    private static final String TR_NUM = "\"1234567890*-";
    private static final String TR_NUM_SHIFT = "é!'^+%&/()=?_";
    private static final String TR_Q = "qwertyuıopğü";
    private static final String TR_Q_SHIFT = "QWERTYUIOPĞÜ";
    private static final String TR_A = "asdfghjklşi";
    private static final String TR_A_SHIFT = "ASDFGHJKLŞİ";
    private static final String TR_Z = "zxcvbnmöç.";
    private static final String TR_Z_SHIFT = "ZXCVBNMÖÇ:";

    private static List<Key> row(String labels, String shift, int[] qnums, String usLabels) {
        List<Key> r = new ArrayList<>();
        int i = 0;
        for (int off = 0; off < labels.length(); ) {
            int cp = labels.codePointAt(off);
            off += Character.charCount(cp);
            String l = new String(Character.toChars(cp));
            String s = shift != null ? shift.substring(shift.offsetByCodePoints(0, i),
                    shift.offsetByCodePoints(0, i + 1)) : null;
            // keysym fallback uses the US character of the same physical key
            int sym = Keysyms.forChar(usLabels.charAt(i));
            r.add(k(l, s, sym, qnums[i], 1));
            i++;
        }
        return r;
    }

    /** Full keyboard: 6 rows. {@code turkish} selects the labels only. */
    public static List<List<Key>> full(boolean turkish) {
        List<List<Key>> rows = new ArrayList<>();

        List<Key> f = new ArrayList<>();
        f.add(k("Esc", Keysyms.ESCAPE, 0x01, 1.25f));
        int[] fq = {0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40, 0x41, 0x42, 0x43, 0x44, 0x57, 0x58};
        for (int i = 1; i <= 12; i++) f.add(k("F" + i, Keysyms.f(i), fq[i - 1], 1));
        f.add(k("Del", Keysyms.DELETE, 0xd3, 1.25f));
        rows.add(f);

        List<Key> n = row(turkish ? TR_NUM : US_NUM, turkish ? TR_NUM_SHIFT : US_NUM_SHIFT, NUM_Q, US_NUM);
        n.add(k("⌫", Keysyms.BACKSPACE, 0x0e, 2));
        rows.add(n);

        List<Key> q = new ArrayList<>();
        q.add(k("Tab", Keysyms.TAB, 0x0f, 1.5f));
        q.addAll(row(turkish ? TR_Q : US_Q, turkish ? TR_Q_SHIFT : US_Q_SHIFT, Q_Q, US_Q));
        q.add(k(turkish ? "," : "\\", turkish ? ";" : "|", '\\', 0x2b, 1.5f));
        rows.add(q);

        List<Key> a = new ArrayList<>();
        a.add(k("Caps", Keysyms.CAPS_LOCK, 0x3a, 1.75f));
        a.addAll(row(turkish ? TR_A : US_A, turkish ? TR_A_SHIFT : US_A_SHIFT, A_Q, US_A));
        a.add(k("Enter", Keysyms.RETURN, 0x1c, 2.25f));
        rows.add(a);

        List<Key> z = new ArrayList<>();
        if (turkish) {
            z.add(mod("Shift", Modifiers.SHIFT, 1.25f));
            z.add(k("<", ">", '<', 0x56, 1)); // ISO 102nd key
        } else {
            z.add(mod("Shift", Modifiers.SHIFT, 2.25f));
        }
        z.addAll(row(turkish ? TR_Z : US_Z, turkish ? TR_Z_SHIFT : US_Z_SHIFT, Z_Q, US_Z));
        z.add(k("↑", Keysyms.UP, 0xc8, 1));
        z.add(mod("Shift", Modifiers.SHIFT, 1.75f));
        rows.add(z);

        List<Key> b = new ArrayList<>();
        b.add(mod("Ctrl", Modifiers.CTRL, 1.25f));
        b.add(mod("Win", Modifiers.WIN, 1.25f));
        b.add(mod("Alt", Modifiers.ALT, 1.25f));
        b.add(k("", Keysyms.forChar(' '), 0x39, 5.25f)); // space
        b.add(mod("AltGr", Modifiers.ALTGR, 1.25f));
        b.add(action("⌨", ACTION_ANDROID_IME, 1.25f));
        b.add(k("←", Keysyms.LEFT, 0xcb, 1));
        b.add(k("↓", Keysyms.DOWN, 0xd0, 1));
        b.add(k("→", Keysyms.RIGHT, 0xcd, 1));
        rows.add(b);
        return rows;
    }

    /** Navigation, shortcuts and mouse buttons. */
    public static List<List<Key>> nav() {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(
                k("Ins", Keysyms.INSERT, 0xd2, 1), k("Home", Keysyms.HOME, 0xc7, 1),
                k("PgUp", Keysyms.PAGE_UP, 0xc9, 1), k("PrtSc", Keysyms.PRINT, 0xb7, 1),
                k("ScrLk", Keysyms.SCROLL_LOCK, 0x46, 1), k("Pause", Keysyms.PAUSE, 0xc6, 1)));
        rows.add(Arrays.asList(
                k("Del", Keysyms.DELETE, 0xd3, 1), k("End", Keysyms.END, 0xcf, 1),
                k("PgDn", Keysyms.PAGE_DOWN, 0xd1, 1), k("Menü", Keysyms.MENU, 0xdd, 1),
                k("NumLk", Keysyms.NUM_LOCK, 0x45, 1), k("Esc", Keysyms.ESCAPE, 0x01, 1)));
        rows.add(Arrays.asList(
                combo("Ctrl+Alt+Del", Keysyms.DELETE, 0xd3, 2, Modifiers.CTRL, Modifiers.ALT),
                combo("Ctrl+Shift+Esc", Keysyms.ESCAPE, 0x01, 2, Modifiers.CTRL, Modifiers.SHIFT),
                k("↑", Keysyms.UP, 0xc8, 1),
                combo("Alt+F4", Keysyms.f(4), 0x3e, 1, Modifiers.ALT)));
        rows.add(Arrays.asList(
                combo("Alt+Tab", Keysyms.TAB, 0x0f, 1, Modifiers.ALT),
                combo("Win+R", 'r', 0x13, 1, Modifiers.WIN),
                combo("Win+D", 'd', 0x20, 1, Modifiers.WIN),
                k("←", Keysyms.LEFT, 0xcb, 1), k("↓", Keysyms.DOWN, 0xd0, 1),
                k("→", Keysyms.RIGHT, 0xcd, 1)));
        rows.add(Arrays.asList(
                mouse("Sol tık", RfbClient.BUTTON_LEFT, 1.5f),
                mouse("Orta", RfbClient.BUTTON_MIDDLE, 1),
                mouse("Sağ tık", RfbClient.BUTTON_RIGHT, 1.5f),
                mouse("Teker ▲", MOUSE_WHEEL_UP, 1),
                mouse("Teker ▼", MOUSE_WHEEL_DOWN, 1)));
        rows.add(Arrays.asList(
                mod("Ctrl", Modifiers.CTRL, 1), mod("Shift", Modifiers.SHIFT, 1),
                mod("Alt", Modifiers.ALT, 1), mod("Win", Modifiers.WIN, 1),
                k("Tab", Keysyms.TAB, 0x0f, 1), k("Enter", Keysyms.RETURN, 0x1c, 1)));
        return rows;
    }

    /** One row shown under the screen while the full keyboard is closed. */
    public static List<List<Key>> quick() {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(
                k("Esc", Keysyms.ESCAPE, 0x01, 1), k("Tab", Keysyms.TAB, 0x0f, 1),
                mod("Ctrl", Modifiers.CTRL, 1), mod("Alt", Modifiers.ALT, 1),
                mod("Shift", Modifiers.SHIFT, 1), mod("Win", Modifiers.WIN, 1),
                k("Del", Keysyms.DELETE, 0xd3, 1), k("←", Keysyms.LEFT, 0xcb, 1),
                k("↑", Keysyms.UP, 0xc8, 1), k("↓", Keysyms.DOWN, 0xd0, 1),
                k("→", Keysyms.RIGHT, 0xcd, 1), k("⏎", Keysyms.RETURN, 0x1c, 1)));
        return rows;
    }
}
