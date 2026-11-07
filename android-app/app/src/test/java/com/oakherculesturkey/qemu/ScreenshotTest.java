package com.oakherculesturkey.qemu;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import com.oakherculesturkey.qemu.core.KeyLayouts;
import com.oakherculesturkey.qemu.core.Modifiers;
import com.oakherculesturkey.qemu.core.VmConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;

/**
 * Renders the real UI with Robolectric's native graphics and writes PNGs to
 * app/build/screenshots, to review layout changes without a device.
 * A guest framebuffer captured from QEMU by tests/app-core can be supplied
 * with -Dqemu.fb=/path/to/file.fb.
 */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36)
public class ScreenshotTest {
    private static final File OUT = new File("build/screenshots");

    private static void save(View v, String name) throws Exception {
        OUT.mkdirs();
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                h > 0 ? View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                        : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void keyboards() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Modifiers m = new Modifiers();
        PcKeyboardView k = new PcKeyboardView(a);
        k.setup(m, null);
        k.setRows(KeyLayouts.full(true));
        layout(k, 1233, 0);
        save(k, "keyboard_tr");
        k.setRows(KeyLayouts.full(false));
        m.press(Modifiers.SHIFT);
        m.release(Modifiers.SHIFT); // one-shot Shift: shows shifted labels
        layout(k, 1233, 0);
        save(k, "keyboard_us_shift");
        m.reset(false);
        k.setRows(KeyLayouts.nav());
        layout(k, 1233, 0);
        save(k, "keyboard_nav");
    }

    private VmConfig sampleVm() throws Exception {
        VmConfig c = VmConfig.template(4);
        c.id = "shot0001";
        VmStore.save(RuntimeEnvironment.getApplication(), c);
        return c;
    }

    private void fillFramebuffer(Activity a) throws Exception {
        Field f = DisplayActivity.class.getDeclaredField("vnc");
        f.setAccessible(true);
        VncView v = (VncView) f.get(a);
        String path = System.getProperty("qemu.fb");
        int w = 720, h = 400;
        int[] px;
        if (path != null && new File(path).exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path)))) {
                w = in.readInt();
                h = in.readInt();
                px = new int[w * h];
                for (int i = 0; i < px.length; i++) px[i] = in.readInt();
            }
        } else {
            px = new int[w * h];
            for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | ((i % w) * 255 / w) << 8;
        }
        v.onResize(w, h);
        v.onUpdate(px, w, 0, 0, w, h);
        Field s = DisplayActivity.class.getDeclaredField("status");
        s.setAccessible(true);
        ((View) s.get(a)).setVisibility(View.GONE);
    }

    private void display(String qualifiers, boolean keyboardOpen, String name) throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        VmConfig c = sampleVm();
        RuntimeEnvironment.getApplication().getSharedPreferences("display", 0).edit()
                .putBoolean("kbd_open", keyboardOpen).putBoolean("tr_" + c.id, true).commit();
        Intent i = new Intent(RuntimeEnvironment.getApplication(), DisplayActivity.class)
                .putExtra(DisplayActivity.EXTRA_ID, c.id);
        ActivityController<DisplayActivity> ac = Robolectric.buildActivity(DisplayActivity.class, i).setup();
        DisplayActivity a = ac.get();
        fillFramebuffer(a);
        ShadowLooper.idleMainLooper();
        View root = a.getWindow().getDecorView();
        root.requestLayout();
        ShadowLooper.idleMainLooper();
        save(root, name);
        ac.pause().stop().destroy();
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void displayPortraitKeyboard() throws Exception {
        display("w411dp-h891dp-port-xxhdpi", true, "display_portrait_keyboard");
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void displayPortraitQuick() throws Exception {
        display("w411dp-h891dp-port-xxhdpi", false, "display_portrait_quickkeys");
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void displayLandscapeKeyboard() throws Exception {
        display("w891dp-h411dp-land-xxhdpi", true, "display_landscape_keyboard");
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void mainList() throws Exception {
        sampleVm();
        VmConfig b = VmConfig.template(0);
        b.id = "shot0002";
        b.cdrom = "/data/alpine-virt-3.22-aarch64.iso";
        VmStore.save(RuntimeEnvironment.getApplication(), b);
        Env.ensureData(RuntimeEnvironment.getApplication());
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        save(ac.get().getWindow().getDecorView(), "main_list");
        assertTrue(new File(OUT, "main_list.png").exists());
    }
}
