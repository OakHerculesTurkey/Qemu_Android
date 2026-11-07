package com.oakherculesturkey.qemu.core;

/** X11 keysyms used by the RFB protocol. */
public final class Keysyms {
    private Keysyms() {}

    public static final int BACKSPACE = 0xff08;
    public static final int TAB = 0xff09;
    public static final int RETURN = 0xff0d;
    public static final int ESCAPE = 0xff1b;
    public static final int DELETE = 0xffff;
    public static final int HOME = 0xff50;
    public static final int LEFT = 0xff51;
    public static final int UP = 0xff52;
    public static final int RIGHT = 0xff53;
    public static final int DOWN = 0xff54;
    public static final int PAGE_UP = 0xff55;
    public static final int PAGE_DOWN = 0xff56;
    public static final int END = 0xff57;
    public static final int INSERT = 0xff63;
    public static final int MENU = 0xff67;
    public static final int F1 = 0xffbe; // F1..F12 are consecutive
    public static final int SHIFT_L = 0xffe1;
    public static final int SHIFT_R = 0xffe2;
    public static final int CONTROL_L = 0xffe3;
    public static final int CONTROL_R = 0xffe4;
    public static final int CAPS_LOCK = 0xffe5;
    public static final int META_L = 0xffe7;
    public static final int ALT_L = 0xffe9;
    public static final int ALT_R = 0xffea;
    public static final int SUPER_L = 0xffeb;
    public static final int PRINT = 0xff61;
    public static final int SCROLL_LOCK = 0xff14;
    public static final int PAUSE = 0xff13;
    public static final int NUM_LOCK = 0xff7f;
    public static final int ISO_LEVEL3_SHIFT = 0xfe03; // AltGr

    public static int f(int n) {
        return F1 + (n - 1);
    }

    /** Keysym for a Unicode character (Latin-1 maps 1:1, others use 0x01000000|cp). */
    public static int forChar(int cp) {
        if (cp == '\n' || cp == '\r') return RETURN;
        if (cp == '\t') return TAB;
        if (cp == '\b') return BACKSPACE;
        if (cp >= 0x20 && cp <= 0x7e) return cp;
        if (cp >= 0xa0 && cp <= 0xff) return cp;
        return 0x01000000 | cp;
    }

    /**
     * Whether the character needs Shift on a US keyboard. QEMU translates
     * keysyms through its keymap but does not synthesize Shift itself, so
     * text coming from a soft keyboard must be wrapped in Shift_L.
     */
    public static boolean needsShift(int cp) {
        if (cp >= 'A' && cp <= 'Z') return true;
        return "~!@#$%^&*()_+{}|:\"<>?".indexOf(cp) >= 0;
    }
}
