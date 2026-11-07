package com.oakherculesturkey.qemu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.oakherculesturkey.qemu.core.KeyLayouts;
import com.oakherculesturkey.qemu.core.KeyLayouts.Key;
import com.oakherculesturkey.qemu.core.Modifiers;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom-drawn on-screen PC keyboard. Keys send real down/up events, so
 * holding a key auto-repeats in the guest, and several fingers can be down at
 * once (Ctrl + C, Shift + arrows, ...).
 */
public class PcKeyboardView extends View {

    interface Callbacks {
        /** KIND_KEY pressed or released. */
        void onKey(Key k, boolean down);

        /** KIND_COMBO released: send the shortcut. */
        void onCombo(Key k);

        /** KIND_MOUSE pressed or released. */
        void onMouse(Key k, boolean down);

        /** KIND_ACTION released. */
        void onAction(Key k);
    }

    private static final int BG = 0xFF0F1114;
    private static final int KEY = 0xFF2B3038;
    private static final int KEY_SPECIAL = 0xFF20242B;
    private static final int KEY_PRESSED = Ui.ACCENT;
    private static final int TEXT = 0xFFF2F4F7;
    private static final int TEXT_DIM = 0xFF8A93A0;

    private List<List<Key>> rows = new ArrayList<>();
    private final List<RectF> rects = new ArrayList<>();
    private final List<Key> flat = new ArrayList<>();
    private final SparseArray<Key> pointers = new SparseArray<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private Modifiers mods;
    private Callbacks callbacks;
    private float rowHeight;
    private final float gap, radius, pad;
    private boolean capsLock;

    public PcKeyboardView(Context c) {
        super(c);
        gap = Ui.dp(c, 3);
        radius = Ui.dp(c, 6);
        pad = Ui.dp(c, 3);
        rowHeight = Ui.dp(c, 42);
        text.setColor(TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        hint.setColor(TEXT_DIM);
        hint.setTextAlign(Paint.Align.LEFT);
        hint.setTextSize(sp(9));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 2));
        stroke.setColor(Ui.ACCENT);
        setBackgroundColor(BG);
        setHapticFeedbackEnabled(true);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    void setup(Modifiers m, Callbacks cb) {
        mods = m;
        callbacks = cb;
        m.addListener(this::postInvalidate);
    }

    void setRows(List<List<Key>> r) {
        releaseAll();
        rows = r;
        requestLayout();
        layoutKeys();
        invalidate();
    }

    void setRowHeight(float px) {
        rowHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(rows.size() * rowHeight + 2 * pad);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys();
    }

    private void layoutKeys() {
        rects.clear();
        flat.clear();
        float w = getWidth() - 2 * pad;
        if (w <= 0) return;
        float y = pad;
        for (List<Key> row : rows) {
            float units = 0;
            for (Key k : row) units += k.width;
            float unit = w / units;
            float x = pad;
            for (Key k : row) {
                float kw = k.width * unit;
                rects.add(new RectF(x, y, x + kw, y + rowHeight));
                flat.add(k);
                x += kw;
            }
            y += rowHeight;
        }
    }

    private boolean shifted() {
        return mods != null && mods.active(Modifiers.SHIFT);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean shift = shifted();
        for (int i = 0; i < flat.size(); i++) {
            Key k = flat.get(i);
            RectF r = rects.get(i);
            tmp.set(r.left + gap / 2, r.top + gap / 2, r.right - gap / 2, r.bottom - gap / 2);
            boolean pressed = pointers.indexOfValue(k) >= 0;
            int modState = k.kind == KeyLayouts.KIND_MOD && mods != null ? mods.state(k.arg) : 0;
            boolean special = k.kind != KeyLayouts.KIND_KEY || k.label.length() > 1;
            int color = special ? KEY_SPECIAL : KEY;
            if (pressed || modState == Modifiers.HELD || modState == Modifiers.LOCKED
                    || (k.qnum == 0x3a && capsLock)) {
                color = KEY_PRESSED;
            }
            fill.setColor(color);
            c.drawRoundRect(tmp, radius, radius, fill);
            if (modState == Modifiers.ONESHOT) c.drawRoundRect(tmp, radius, radius, stroke);

            String label = k.label;
            if (k.isLetter() && (shift ^ capsLock)) {
                label = k.shiftLabel != null ? k.shiftLabel : label.toUpperCase();
            } else if (!k.isLetter() && shift && k.shiftLabel != null) {
                label = k.shiftLabel;
            }
            // single characters big and bold; words (Esc, F10, Ctrl, ...) one consistent size
            boolean single = label.codePointCount(0, label.length()) == 1;
            text.setTextSize(sp(single ? 17 : (label.length() > 6 ? 11 : 13)));
            text.setTypeface(single ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            text.setColor(modState == Modifiers.ONESHOT ? Ui.ACCENT : TEXT);
            // shrink long labels to fit the key
            float maxW = tmp.width() - Ui.dp(getContext(), 4);
            while (text.measureText(label) > maxW && text.getTextSize() > sp(7)) {
                text.setTextSize(text.getTextSize() - 1);
            }
            float ty = tmp.centerY() - (text.descent() + text.ascent()) / 2;
            c.drawText(label, tmp.centerX(), ty, text);
            // secondary (shifted) symbol, top-left, like a real keycap
            if (!shift && k.shiftLabel != null && !k.isLetter()) {
                c.drawText(k.shiftLabel, tmp.left + Ui.dp(getContext(), 4),
                        tmp.top + hint.getTextSize() + Ui.dp(getContext(), 1), hint);
            }
        }
    }

    private Key hit(float x, float y) {
        for (int i = 0; i < rects.size(); i++) {
            if (rects.get(i).contains(x, y)) return flat.get(i);
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Key k = hit(e.getX(idx), e.getY(idx));
                if (k != null) {
                    pointers.put(e.getPointerId(idx), k);
                    down(k);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                Key k = pointers.get(id);
                if (k != null) {
                    pointers.remove(id);
                    up(k);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
            default:
                return true;
        }
    }

    private void releaseAll() {
        for (int i = 0; i < pointers.size(); i++) up(pointers.valueAt(i));
        pointers.clear();
        invalidate();
    }

    private void down(Key k) {
        switch (k.kind) {
            case KeyLayouts.KIND_KEY:
                if (k.qnum == 0x3a) capsLock = !capsLock;
                if (mods != null) mods.keyDown();
                if (callbacks != null) callbacks.onKey(k, true);
                break;
            case KeyLayouts.KIND_MOD:
                if (mods != null) mods.press(k.arg);
                break;
            case KeyLayouts.KIND_MOUSE:
                if (callbacks != null) callbacks.onMouse(k, true);
                break;
            default:
                break;
        }
    }

    private void up(Key k) {
        switch (k.kind) {
            case KeyLayouts.KIND_KEY:
                if (callbacks != null) callbacks.onKey(k, false);
                if (mods != null) mods.keyUp();
                break;
            case KeyLayouts.KIND_MOD:
                if (mods != null) mods.release(k.arg);
                break;
            case KeyLayouts.KIND_COMBO:
                if (mods != null) mods.keyDown();
                if (callbacks != null) callbacks.onCombo(k);
                if (mods != null) mods.keyUp();
                break;
            case KeyLayouts.KIND_MOUSE:
                if (callbacks != null) callbacks.onMouse(k, false);
                break;
            case KeyLayouts.KIND_ACTION:
                if (callbacks != null) callbacks.onAction(k);
                break;
            default:
                break;
        }
    }
}
