package com.oakherculesturkey.qemu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.oakherculesturkey.qemu.core.Keysyms;
import com.oakherculesturkey.qemu.core.Modifiers;
import com.oakherculesturkey.qemu.core.RfbClient;

/**
 * Shows the guest framebuffer and turns touch / keyboard input into RFB
 * events.
 *
 * One finger, "touch" mode: the finger is the mouse (tap = click, long press =
 * right click, drag = left drag).
 * One finger, "touchpad" mode: relative cursor (tap = click, long press then
 * move = drag, two-finger tap = right click).
 * Two fingers: pinch = zoom; drag = scroll wheel at 1x, pan when zoomed in.
 */
public class VncView extends View {

    interface Listener {
        void onZoomChanged(float zoom);
    }

    private final Object lock = new Object();
    private Bitmap bitmap;
    private int fbW, fbH;
    private final RectF dst = new RectF();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile RfbClient client;
    private Modifiers mods;
    private Listener listener;

    private boolean touchpad;
    private float cursorX, cursorY;
    private int buttons;
    private boolean dragging;
    private boolean multiTouch;
    private float lastX, lastY;
    private final GestureDetector gestures;
    private String composing = "";

    // zoom / pan
    private float zoom = 1f, panX, panY;
    private static final int TWO_NONE = 0, TWO_UNDECIDED = 1, TWO_SCROLL = 2, TWO_PAN = 3, TWO_ZOOM = 4;
    private int twoMode = TWO_NONE;
    private float startSpan, lastSpan, focusX, focusY, startFocusX, startFocusY, scrollAccum;

    public VncView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        cursorPaint.setColor(Color.BLACK);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(Ui.dp(c, 1.5f));
        cursorFill.setColor(Color.WHITE);
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
                if (multiTouch || dragging) return;
                if (touchpad) {
                    dragging = true; // drag at the cursor position
                    buttons = RfbClient.BUTTON_LEFT;
                    sendPointer();
                } else {
                    moveTo(e.getX(), e.getY());
                    click(RfbClient.BUTTON_RIGHT);
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
        });
    }

    void setClient(RfbClient c) {
        client = c;
    }

    void setModifiers(Modifiers m) {
        mods = m;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTouchpad(boolean on) {
        touchpad = on;
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
        post(this::clampPan);
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
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        clampPan();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        synchronized (lock) {
            if (bitmap == null) return;
            computeDst();
            paint.setFilterBitmap(dst.width() < fbW * 1.5f); // crisp pixels when zoomed in
            canvas.drawBitmap(bitmap, null, dst, paint);
            if (touchpad) {
                float sx = dst.left + cursorX * dst.width() / fbW;
                float sy = dst.top + cursorY * dst.height() / fbH;
                float s = Ui.dp(getContext(), 1);
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(sx, sy);
                p.lineTo(sx, sy + 17 * s);
                p.lineTo(sx + 4.5f * s, sy + 13 * s);
                p.lineTo(sx + 8 * s, sy + 20 * s);
                p.lineTo(sx + 10.5f * s, sy + 19 * s);
                p.lineTo(sx + 7 * s, sy + 12 * s);
                p.lineTo(sx + 12.5f * s, sy + 12 * s);
                p.close();
                canvas.drawPath(p, cursorFill);
                canvas.drawPath(p, cursorPaint);
            }
        }
    }

    private float baseScale() {
        float vw = getWidth(), vh = getHeight();
        if (fbW == 0 || fbH == 0 || vw == 0 || vh == 0) return 1;
        return Math.min(vw / fbW, vh / fbH);
    }

    private void computeDst() {
        float vw = getWidth(), vh = getHeight();
        if (fbW == 0 || fbH == 0 || vw == 0 || vh == 0) {
            dst.set(0, 0, 0, 0);
            return;
        }
        float s = baseScale() * zoom;
        float w = fbW * s, h = fbH * s;
        float left = (vw - w) / 2 + panX, top = baseTop(h) + panY;
        dst.set(left, top, left + w, top + h);
    }

    /** Images shorter than the view sit at the top (free space goes below). */
    private float baseTop(float h) {
        float vh = getHeight();
        return h < vh ? 0 : (vh - h) / 2;
    }

    private void clampPan() {
        float vw = getWidth(), vh = getHeight();
        float s = baseScale() * zoom;
        float mx = Math.max(0, (fbW * s - vw) / 2), my = Math.max(0, (fbH * s - vh) / 2);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
    }

    void resetZoom() {
        zoom = 1;
        panX = panY = 0;
        invalidate();
        if (listener != null) listener.onZoomChanged(zoom);
    }

    float getZoom() {
        return zoom;
    }

    private void zoomAt(float fx, float fy, float factor) {
        computeDst();
        if (dst.width() <= 0) return;
        float u = (fx - dst.left) / dst.width(), v = (fy - dst.top) / dst.height();
        float nz = Math.max(1f, Math.min(5f, zoom * factor));
        if (nz == zoom) return;
        zoom = nz;
        float s = baseScale() * zoom;
        float w = fbW * s, h = fbH * s;
        panX = fx - u * w - (getWidth() - w) / 2;
        panY = fy - v * h - baseTop(h);
        clampPan();
        invalidate();
        if (listener != null) listener.onZoomChanged(zoom);
    }

    /** In touchpad mode, keep the cursor on screen while zoomed in. */
    private void followCursor() {
        if (zoom <= 1f) return;
        computeDst();
        float sx = dst.left + cursorX * dst.width() / fbW;
        float sy = dst.top + cursorY * dst.height() / fbH;
        float m = Ui.dp(getContext(), 40);
        if (sx < m) panX += m - sx;
        if (sx > getWidth() - m) panX -= sx - (getWidth() - m);
        if (sy < m) panY += m - sy;
        if (sy > getHeight() - m) panY -= sy - (getHeight() - m);
        clampPan();
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

    /** Mouse button from the on-screen keyboard (held while the key is held). */
    void mouseButton(int mask, boolean down) {
        buttons = down ? (buttons | mask) : (buttons & ~mask);
        sendPointer();
    }

    void wheel(int steps) {
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
            twoMode = TWO_NONE;
            lastX = e.getX();
            lastY = e.getY();
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            multiTouch = true;
            twoMode = TWO_UNDECIDED;
            startSpan = lastSpan = span(e);
            startFocusX = focusX = avg(e, true);
            startFocusY = focusY = avg(e, false);
            scrollAccum = 0;
        }
        gestures.onTouchEvent(e);

        if (action == MotionEvent.ACTION_MOVE) {
            if (e.getPointerCount() >= 2 && twoMode != TWO_NONE) {
                twoFingerMove(e);
            } else if (!multiTouch) {
                oneFingerMove(e);
            }
        }
        if (action == MotionEvent.ACTION_POINTER_UP && touchpad && e.getPointerCount() == 2
                && twoMode == TWO_UNDECIDED && e.getEventTime() - e.getDownTime() < 300) {
            click(RfbClient.BUTTON_RIGHT); // two-finger tap
        }
        if (action == MotionEvent.ACTION_POINTER_UP) twoMode = TWO_NONE;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging && buttons != 0) {
                buttons = 0;
                sendPointer();
            }
            dragging = false;
        }
        return true;
    }

    private void oneFingerMove(MotionEvent e) {
        float dx = e.getX() - lastX, dy = e.getY() - lastY;
        if (touchpad) {
            computeDst();
            if (dst.width() > 0) {
                // acceleration makes a small phone screen usable
                float speed = (float) Math.hypot(dx, dy);
                float accel = Math.min(3f, 1f + speed / Ui.dp(getContext(), 30));
                float k = accel / (baseScale() * Math.max(1f, zoom * 0.7f));
                cursorX = clamp(cursorX + dx * k, fbW);
                cursorY = clamp(cursorY + dy * k, fbH);
                sendPointer();
                followCursor();
                invalidate();
            }
            lastX = e.getX();
            lastY = e.getY();
        } else {
            float slop = Ui.dp(getContext(), 8);
            if (!dragging && Math.hypot(dx, dy) > slop) {
                // finger drag = left-button drag from where it went down
                dragging = true;
                moveTo(lastX, lastY);
                buttons |= RfbClient.BUTTON_LEFT;
                sendPointer();
            }
            if (dragging) moveTo(e.getX(), e.getY());
        }
    }

    private void twoFingerMove(MotionEvent e) {
        float sp = span(e), fx = avg(e, true), fy = avg(e, false);
        if (twoMode == TWO_UNDECIDED) {
            float d = Ui.dp(getContext(), 20);
            if (Math.abs(sp - startSpan) > d) {
                twoMode = TWO_ZOOM;
            } else if (Math.hypot(fx - startFocusX, fy - startFocusY) > d / 2) {
                twoMode = zoom > 1.01f ? TWO_PAN : TWO_SCROLL;
            }
        }
        if (twoMode == TWO_ZOOM) {
            if (lastSpan > 0) zoomAt(fx, fy, sp / lastSpan);
            panX += fx - focusX;
            panY += fy - focusY;
            clampPan();
            invalidate();
        } else if (twoMode == TWO_PAN) {
            panX += fx - focusX;
            panY += fy - focusY;
            clampPan();
            invalidate();
        } else if (twoMode == TWO_SCROLL) {
            scrollAccum += fy - focusY;
            float step = Ui.dp(getContext(), 24);
            int steps = (int) (scrollAccum / step);
            if (steps != 0) {
                scrollAccum -= steps * step;
                wheel(steps);
            }
        }
        lastSpan = sp;
        focusX = fx;
        focusY = fy;
    }

    private static float span(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float avg(MotionEvent e, boolean x) {
        float s = 0;
        int n = Math.min(2, e.getPointerCount());
        for (int i = 0; i < n; i++) s += x ? e.getX(i) : e.getY(i);
        return s / n;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        // physical mouse / trackpad (USB OTG, Bluetooth, DeX)
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
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

    /** Physical-key event from the on-screen PC keyboard. */
    void scancode(int keysym, int qnum, boolean down) {
        RfbClient c = client;
        if (c != null) c.sendScancode(keysym, qnum, down);
    }

    /** One key press by keysym; on-screen modifiers are already held in the guest. */
    void tap(int keysym) {
        RfbClient c = client;
        if (c == null) return;
        if (mods != null) mods.keyDown();
        c.tapKey(keysym);
        if (mods != null) mods.keyUp();
    }

    /** Text from the Android soft keyboard. */
    void sendText(CharSequence text) {
        RfbClient c = client;
        if (c == null) return;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            int sym = Keysyms.forChar(cp);
            boolean shiftHeld = mods != null && mods.active(Modifiers.SHIFT);
            boolean otherMods = mods != null && (mods.active(Modifiers.CTRL)
                    || mods.active(Modifiers.ALT) || mods.active(Modifiers.WIN));
            if (mods != null) mods.keyDown();
            if (otherMods && cp >= 'A' && cp <= 'Z') {
                c.tapKey(Character.toLowerCase(cp)); // Ctrl+C, not Ctrl+Shift+C
            } else if (Keysyms.needsShift(cp) && !shiftHeld) {
                c.tapKey(sym, Keysyms.SHIFT_L);
            } else {
                c.tapKey(sym);
            }
            if (mods != null) mods.keyUp();
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
        RfbClient c = client;
        if (c == null) return true;
        int sym = specialKeysym(keyCode);
        if (sym != 0) {
            if (isModifier(keyCode)) c.sendKey(sym, true);
            else tap(sym);
            return true;
        }
        int uc = e.getUnicodeChar(e.getMetaState()
                & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK));
        if (uc != 0 && (uc & COMBINING_ACCENT) == 0) {
            if (e.isShiftPressed()) {
                tap(Keysyms.forChar(uc)); // Shift_L is already held down
            } else {
                sendText(new String(Character.toChars(uc)));
            }
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private static final int COMBINING_ACCENT = 0x80000000;

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
