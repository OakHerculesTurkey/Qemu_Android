package com.oakherculesturkey.qemu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.oakherculesturkey.qemu.core.Keysyms;
import com.oakherculesturkey.qemu.core.RfbClient;

/**
 * Shows the guest framebuffer and turns touch / keyboard input into RFB
 * events. Two pointer modes:
 *  - touch:    the finger is the mouse (tap = click, long press = right click,
 *              drag = left drag, two fingers = scroll wheel)
 *  - touchpad: relative cursor like a laptop touchpad (tap = click,
 *              two-finger tap = right click, long press + move = drag)
 */
public class VncView extends View {

    /** Supplies one-shot modifiers selected on the toolbar (Ctrl, Alt, ...). */
    interface ModifierSource {
        int[] takeModifiers();
    }

    private final Object lock = new Object();
    private Bitmap bitmap;
    private int fbW, fbH;
    private final RectF dst = new RectF();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile RfbClient client;
    private ModifierSource modifiers;

    private boolean touchpad;
    private float cursorX, cursorY;
    private int buttons;
    private boolean dragging;
    private boolean multiTouch;
    private float lastX, lastY, scrollAccum;
    private final GestureDetector gestures;
    private String composing = "";

    public VncView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        cursorPaint.setColor(Color.WHITE);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(Ui.dp(c, 2));
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (multiTouch) return true;
                if (!touchpad) moveTo(e.getX(), e.getY());
                click(RfbClient.BUTTON_LEFT);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (multiTouch) return;
                if (touchpad) {
                    // start a drag at the cursor position
                    dragging = true;
                    buttons = RfbClient.BUTTON_LEFT;
                    sendPointer();
                } else {
                    moveTo(e.getX(), e.getY());
                    click(RfbClient.BUTTON_RIGHT);
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
        });
        gestures.setIsLongpressEnabled(true);
    }

    void setClient(RfbClient c) {
        client = c;
    }

    void setModifierSource(ModifierSource m) {
        modifiers = m;
    }

    void setTouchpad(boolean on) {
        touchpad = on;
        if (on && fbW > 0) {
            cursorX = fbW / 2f;
            cursorY = fbH / 2f;
        }
        invalidate();
    }

    boolean isTouchpad() {
        return touchpad;
    }

    // ---- Framebuffer (called from the RFB thread) ---------------------------

    void onResize(int w, int h) {
        synchronized (lock) {
            fbW = w;
            fbH = h;
            bitmap = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            cursorX = w / 2f;
            cursorY = h / 2f;
        }
        postInvalidate();
    }

    void onUpdate(int[] fb, int stride, int x, int y, int w, int h) {
        synchronized (lock) {
            if (bitmap == null || bitmap.getWidth() != stride) return;
            bitmap.setPixels(fb, y * stride + x, stride, x, y, w, h);
        }
    }

    void onFrameComplete() {
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        synchronized (lock) {
            if (bitmap == null) return;
            computeDst();
            canvas.drawBitmap(bitmap, null, dst, paint);
            if (touchpad) {
                float sx = dst.left + cursorX * dst.width() / fbW;
                float sy = dst.top + cursorY * dst.height() / fbH;
                float r = Ui.dp(getContext(), 7);
                canvas.drawCircle(sx, sy, r, cursorPaint);
                canvas.drawPoint(sx, sy, cursorPaint);
            }
        }
    }

    private void computeDst() {
        float vw = getWidth(), vh = getHeight();
        if (fbW == 0 || fbH == 0 || vw == 0 || vh == 0) {
            dst.set(0, 0, 0, 0);
            return;
        }
        float s = Math.min(vw / fbW, vh / fbH);
        float w = fbW * s, h = fbH * s;
        dst.set((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2);
    }

    // ---- Pointer -------------------------------------------------------------

    private void moveTo(float vx, float vy) {
        computeDst();
        if (dst.width() <= 0) return;
        cursorX = clamp((vx - dst.left) * fbW / dst.width(), fbW);
        cursorY = clamp((vy - dst.top) * fbH / dst.height(), fbH);
        sendPointer();
    }

    private static float clamp(float v, int max) {
        return Math.max(0, Math.min(v, max - 1));
    }

    private void sendPointer() {
        RfbClient c = client;
        if (c != null) c.sendPointer(Math.round(cursorX), Math.round(cursorY), buttons);
    }

    private void click(int button) {
        RfbClient c = client;
        if (c == null) return;
        int x = Math.round(cursorX), y = Math.round(cursorY);
        c.sendPointer(x, y, buttons | button);
        c.sendPointer(x, y, buttons);
        if (touchpad) invalidate();
    }

    private void wheel(int steps) {
        RfbClient c = client;
        if (c == null) return;
        int b = steps > 0 ? RfbClient.WHEEL_UP : RfbClient.WHEEL_DOWN;
        int x = Math.round(cursorX), y = Math.round(cursorY);
        for (int i = 0; i < Math.abs(steps); i++) {
            c.sendPointer(x, y, buttons | b);
            c.sendPointer(x, y, buttons);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        requestFocus();
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            dragging = false;
            lastX = e.getX();
            lastY = e.getY();
            scrollAccum = 0;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            lastY = avgY(e);
            scrollAccum = 0;
        }
        gestures.onTouchEvent(e);

        if (action == MotionEvent.ACTION_MOVE) {
            if (e.getPointerCount() >= 2) {
                float y = avgY(e);
                scrollAccum += y - lastY;
                lastY = y;
                float step = Ui.dp(getContext(), 24);
                int steps = (int) (scrollAccum / step);
                if (steps != 0) {
                    scrollAccum -= steps * step;
                    wheel(steps);
                }
            } else if (!multiTouch) {
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (touchpad) {
                    computeDst();
                    if (dst.width() > 0) {
                        // a bit of acceleration makes a small phone screen usable
                        float speed = (float) Math.hypot(dx, dy);
                        float accel = Math.min(3f, 1f + speed / Ui.dp(getContext(), 30));
                        cursorX = clamp(cursorX + dx * accel * fbW / dst.width(), fbW);
                        cursorY = clamp(cursorY + dy * accel * fbH / dst.height(), fbH);
                        sendPointer();
                        invalidate();
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                } else {
                    float slop = Ui.dp(getContext(), 8);
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        // finger drag = left-button drag starting where it went down
                        dragging = true;
                        moveTo(lastX, lastY);
                        buttons = RfbClient.BUTTON_LEFT;
                        sendPointer();
                    }
                    if (dragging) moveTo(e.getX(), e.getY());
                }
            }
        }
        if (action == MotionEvent.ACTION_POINTER_UP && touchpad && e.getPointerCount() == 2
                && Math.abs(scrollAccum) < Ui.dp(getContext(), 6)
                && e.getEventTime() - e.getDownTime() < 300) {
            click(RfbClient.BUTTON_RIGHT);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (buttons != 0) {
                buttons = 0;
                sendPointer();
            }
            dragging = false;
        }
        return true;
    }

    private static float avgY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        // physical mouse / trackpad (OTG, Bluetooth, DeX-like setups)
        if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_MOVE) {
                moveTo(e.getX(), e.getY());
                return true;
            }
            if (a == MotionEvent.ACTION_SCROLL) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0) wheel(v > 0 ? 1 : -1);
                return true;
            }
            if (a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) {
                int bs = e.getButtonState();
                buttons = 0;
                if ((bs & MotionEvent.BUTTON_PRIMARY) != 0) buttons |= RfbClient.BUTTON_LEFT;
                if ((bs & MotionEvent.BUTTON_SECONDARY) != 0) buttons |= RfbClient.BUTTON_RIGHT;
                if ((bs & MotionEvent.BUTTON_TERTIARY) != 0) buttons |= RfbClient.BUTTON_MIDDLE;
                moveTo(e.getX(), e.getY());
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    // ---- Keyboard -------------------------------------------------------------

    /** Sends one key press wrapped in the toolbar's sticky modifiers. */
    void tap(int keysym) {
        RfbClient c = client;
        if (c == null) return;
        int[] mods = modifiers != null ? modifiers.takeModifiers() : new int[0];
        c.tapKey(keysym, mods);
    }

    void tapCombo(int keysym, int... mods) {
        RfbClient c = client;
        if (c != null) c.tapKey(keysym, mods);
    }

    /** Text from the soft keyboard. */
    void sendText(CharSequence text) {
        RfbClient c = client;
        if (c == null) return;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            int sym = Keysyms.forChar(cp);
            int[] sticky = modifiers != null ? modifiers.takeModifiers() : new int[0];
            if (Keysyms.needsShift(cp)) {
                int[] m = new int[sticky.length + 1];
                System.arraycopy(sticky, 0, m, 0, sticky.length);
                m[sticky.length] = Keysyms.SHIFT_L;
                c.tapKey(sym, m);
            } else if (sticky.length > 0 && cp >= 'A' && cp <= 'Z') {
                c.tapKey(Character.toLowerCase(cp), sticky);
            } else {
                c.tapKey(sym, sticky);
            }
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE;
        composing = "";
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                applyComposing(text.toString());
                composing = "";
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                applyComposing(text.toString());
                return true;
            }

            @Override
            public boolean finishComposingText() {
                composing = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < Math.max(1, before); i++) tap(Keysyms.BACKSPACE);
                return true;
            }
        };
    }

    /** Sends only the difference between the old and new composing text. */
    private void applyComposing(String now) {
        String old = composing;
        int common = 0;
        while (common < old.length() && common < now.length()
                && old.charAt(common) == now.charAt(common)) common++;
        for (int i = common; i < old.length(); i++) tap(Keysyms.BACKSPACE);
        if (common < now.length()) sendText(now.substring(common));
        composing = now;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (isSystemKey(keyCode)) return super.onKeyDown(keyCode, e);
        int sym = specialKeysym(keyCode);
        RfbClient c = client;
        if (c == null) return true;
        if (sym != 0) {
            if (isModifier(keyCode)) {
                c.sendKey(sym, true);
            } else {
                tap(sym);
            }
            return true;
        }
        int uc = e.getUnicodeChar(e.getMetaState()
                & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK));
        if (uc != 0 && (uc & KeyCharacterMapCombining) == 0) {
            boolean physicalShift = e.isShiftPressed();
            if (physicalShift) {
                c.tapKey(Keysyms.forChar(uc)); // Shift_L is already held down
            } else {
                sendText(new String(Character.toChars(uc)));
            }
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private static final int KeyCharacterMapCombining = 0x80000000;

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (isSystemKey(keyCode)) return super.onKeyUp(keyCode, e);
        if (isModifier(keyCode)) {
            RfbClient c = client;
            if (c != null) c.sendKey(specialKeysym(keyCode), false);
        }
        return true;
    }

    private static boolean isSystemKey(int k) {
        return k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_VOLUME_UP
                || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE
                || k == KeyEvent.KEYCODE_POWER || k == KeyEvent.KEYCODE_HOME
                || k == KeyEvent.KEYCODE_APP_SWITCH;
    }

    private static boolean isModifier(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return true;
            default:
                return false;
        }
    }

    static int specialKeysym(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DEL: return Keysyms.BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return Keysyms.DELETE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return Keysyms.RETURN;
            case KeyEvent.KEYCODE_TAB: return Keysyms.TAB;
            case KeyEvent.KEYCODE_ESCAPE: return Keysyms.ESCAPE;
            case KeyEvent.KEYCODE_SPACE: return ' ';
            case KeyEvent.KEYCODE_DPAD_UP: return Keysyms.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Keysyms.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Keysyms.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Keysyms.RIGHT;
            case KeyEvent.KEYCODE_MOVE_HOME: return Keysyms.HOME;
            case KeyEvent.KEYCODE_MOVE_END: return Keysyms.END;
            case KeyEvent.KEYCODE_PAGE_UP: return Keysyms.PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return Keysyms.PAGE_DOWN;
            case KeyEvent.KEYCODE_INSERT: return Keysyms.INSERT;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return Keysyms.SHIFT_L;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return Keysyms.SHIFT_R;
            case KeyEvent.KEYCODE_CTRL_LEFT: return Keysyms.CONTROL_L;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return Keysyms.CONTROL_R;
            case KeyEvent.KEYCODE_ALT_LEFT: return Keysyms.ALT_L;
            case KeyEvent.KEYCODE_ALT_RIGHT: return Keysyms.ALT_R;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT: return Keysyms.SUPER_L;
            case KeyEvent.KEYCODE_CAPS_LOCK: return Keysyms.CAPS_LOCK;
            case KeyEvent.KEYCODE_SYSRQ: return Keysyms.PRINT;
            case KeyEvent.KEYCODE_BREAK: return Keysyms.PAUSE;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return Keysyms.SCROLL_LOCK;
            case KeyEvent.KEYCODE_NUM_LOCK: return Keysyms.NUM_LOCK;
            case KeyEvent.KEYCODE_MENU: return Keysyms.MENU;
            default:
                if (k >= KeyEvent.KEYCODE_F1 && k <= KeyEvent.KEYCODE_F12)
                    return Keysyms.f(k - KeyEvent.KEYCODE_F1 + 1);
                return 0;
        }
    }
}
