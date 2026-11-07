package com.oakherculesturkey.qemu.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Modifier keys (Ctrl, Alt, Shift, Win, AltGr) shared by every on-screen key
 * source. Each modifier is really pressed in the guest while active:
 *
 *  - hold it while pressing another key           -> normal chord
 *  - tap it                                       -> one-shot: applies to the next key
 *  - tap it twice                                 -> locked until tapped again
 */
public final class Modifiers {
    public static final int CTRL = 0, ALT = 1, SHIFT = 2, WIN = 3, ALTGR = 4;
    public static final int COUNT = 5;
    public static final int[] SYMS = {Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.SHIFT_L,
            Keysyms.SUPER_L, Keysyms.ISO_LEVEL3_SHIFT};
    public static final int[] QNUMS = {0x1d, 0x38, 0x2a, 0xdb, 0xb8};

    public static final int OFF = 0, HELD = 1, ONESHOT = 2, LOCKED = 3;

    /** Delivers key events to the guest. */
    public interface Sink {
        void key(int keysym, int qnum, boolean down);
    }

    private final int[] state = new int[COUNT];
    private final boolean[] usedWhileHeld = new boolean[COUNT];
    private final boolean[] swallowRelease = new boolean[COUNT];
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Sink sink;

    public void setSink(Sink s) {
        sink = s;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public synchronized int state(int m) {
        return state[m];
    }

    public synchronized boolean active(int m) {
        return state[m] != OFF;
    }

    private void send(int m, boolean down) {
        Sink s = sink;
        if (s != null) s.key(SYMS[m], QNUMS[m], down);
    }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }

    /** Finger down on a modifier key. */
    public void press(int m) {
        synchronized (this) {
            swallowRelease[m] = false;
            switch (state[m]) {
                case OFF:
                    send(m, true);
                    state[m] = HELD;
                    usedWhileHeld[m] = false;
                    break;
                case ONESHOT:
                    state[m] = LOCKED; // second tap locks
                    swallowRelease[m] = true;
                    break;
                case LOCKED:
                    send(m, false);
                    state[m] = OFF;
                    swallowRelease[m] = true;
                    break;
                default:
                    break;
            }
        }
        changed();
    }

    /** Finger up on a modifier key. */
    public void release(int m) {
        synchronized (this) {
            if (swallowRelease[m]) {
                swallowRelease[m] = false;
            } else if (state[m] == HELD) {
                if (usedWhileHeld[m]) {
                    send(m, false);
                    state[m] = OFF;
                } else {
                    state[m] = ONESHOT; // stays pressed for the next key
                }
            }
        }
        changed();
    }

    /** A non-modifier key went down: held modifiers become part of a chord. */
    public synchronized void keyDown() {
        for (int m = 0; m < COUNT; m++) if (state[m] == HELD) usedWhileHeld[m] = true;
    }

    /** A non-modifier key was released: one-shot modifiers are done. */
    public void keyUp() {
        boolean any = false;
        synchronized (this) {
            for (int m = 0; m < COUNT; m++) {
                if (state[m] == ONESHOT) {
                    send(m, false);
                    state[m] = OFF;
                    any = true;
                }
            }
        }
        if (any) changed();
    }

    /** Releases everything (e.g. when the VNC connection is re-established). */
    public void reset(boolean sendUps) {
        synchronized (this) {
            for (int m = 0; m < COUNT; m++) {
                if (state[m] != OFF && sendUps) send(m, false);
                state[m] = OFF;
            }
        }
        changed();
    }
}
