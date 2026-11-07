package com.oakherculesturkey.qemu.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * One virtual machine definition. Plain Java (no Android APIs) so that it can
 * be unit-tested on the build machine; persisted as a .properties file.
 */
public final class VmConfig {
    public static final String ARCH_AARCH64 = "aarch64";
    public static final String ARCH_X86_64 = "x86_64";

    public String id = "";
    public String name = "Yeni VM";
    public String arch = ARCH_AARCH64;
    public String machine = "virt,gic-version=3";
    public String cpu = "max,pauth-impdef=on";
    public int smp = 4;
    public int memMb = 2048;
    /** uefi | bios | none */
    public String firmware = "uefi";

    public String disk = "";
    /** virtio | nvme | ide | usb */
    public String diskIf = "virtio";
    public String cdrom = "";
    /** disk | cdrom */
    public String bootFrom = "disk";

    public String kernel = "";
    public String initrd = "";
    public String append = "";

    /** vnc | serial */
    public String display = "vnc";
    /** virtio-gpu-pci | ramfb | std | cirrus-vga | virtio-vga | none */
    public String gpu = "virtio-gpu-pci";
    /** Whether keyboard/pointer devices are added at all (VNC display only). */
    public boolean usbInput = true;
    /**
     * Keyboard and pointer:
     *  xhci   - USB 3 keyboard + tablet (modern guests, the only choice on ARM)
     *  legacy - PS/2 keyboard + USB 1.1 (UHCI) tablet: Windows XP/7 and other
     *           guests without an xHCI driver
     *  ps2    - PS/2 keyboard and mouse only (relative mouse)
     */
    public String input = "xhci";

    /** user | none */
    public String network = "user";
    public String nic = "virtio-net-pci";
    /** Comma separated hostfwd rules, e.g. "tcp::2222-:22,tcp::8080-:80" */
    public String hostfwd = "";

    /** tcg | kvm */
    public String accel = "tcg";
    /** default | multi | single */
    public String tcgThread = "default";
    /** TCG translation cache size in MiB, 0 = QEMU default */
    public int tbSizeMb = 0;

    public String extraArgs = "";

    public boolean isAarch64() {
        return ARCH_AARCH64.equals(arch);
    }

    /** Sensible defaults for a freshly selected architecture. */
    public void applyArchDefaults() {
        if (isAarch64()) {
            machine = "virt,gic-version=3";
            cpu = "max,pauth-impdef=on";
            firmware = "uefi";
            gpu = "virtio-gpu-pci";
            input = "xhci"; // the virt board has no PS/2
            if ("ide".equals(diskIf)) diskIf = "virtio";
        } else {
            machine = "q35";
            cpu = "max";
            firmware = "bios";
            gpu = "std";
        }
    }

    public VmConfig copy() {
        VmConfig c = new VmConfig();
        c.fromProperties(toProperties());
        return c;
    }

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("id", id);
        p.setProperty("name", name);
        p.setProperty("arch", arch);
        p.setProperty("machine", machine);
        p.setProperty("cpu", cpu);
        p.setProperty("smp", Integer.toString(smp));
        p.setProperty("memMb", Integer.toString(memMb));
        p.setProperty("firmware", firmware);
        p.setProperty("disk", disk);
        p.setProperty("diskIf", diskIf);
        p.setProperty("cdrom", cdrom);
        p.setProperty("bootFrom", bootFrom);
        p.setProperty("kernel", kernel);
        p.setProperty("initrd", initrd);
        p.setProperty("append", append);
        p.setProperty("display", display);
        p.setProperty("gpu", gpu);
        p.setProperty("usbInput", Boolean.toString(usbInput));
        p.setProperty("input", input);
        p.setProperty("network", network);
        p.setProperty("nic", nic);
        p.setProperty("hostfwd", hostfwd);
        p.setProperty("accel", accel);
        p.setProperty("tcgThread", tcgThread);
        p.setProperty("tbSizeMb", Integer.toString(tbSizeMb));
        p.setProperty("extraArgs", extraArgs);
        return p;
    }

    public void fromProperties(Properties p) {
        id = p.getProperty("id", id);
        name = p.getProperty("name", name);
        arch = p.getProperty("arch", arch);
        machine = p.getProperty("machine", machine);
        cpu = p.getProperty("cpu", cpu);
        smp = parseInt(p.getProperty("smp"), smp);
        memMb = parseInt(p.getProperty("memMb"), memMb);
        firmware = p.getProperty("firmware", firmware);
        disk = p.getProperty("disk", disk);
        diskIf = p.getProperty("diskIf", diskIf);
        cdrom = p.getProperty("cdrom", cdrom);
        bootFrom = p.getProperty("bootFrom", bootFrom);
        kernel = p.getProperty("kernel", kernel);
        initrd = p.getProperty("initrd", initrd);
        append = p.getProperty("append", append);
        display = p.getProperty("display", display);
        gpu = p.getProperty("gpu", gpu);
        usbInput = Boolean.parseBoolean(p.getProperty("usbInput", Boolean.toString(usbInput)));
        input = p.getProperty("input", input);
        network = p.getProperty("network", network);
        nic = p.getProperty("nic", nic);
        hostfwd = p.getProperty("hostfwd", hostfwd);
        accel = p.getProperty("accel", accel);
        tcgThread = p.getProperty("tcgThread", tcgThread);
        tbSizeMb = parseInt(p.getProperty("tbSizeMb"), tbSizeMb);
        extraArgs = p.getProperty("extraArgs", extraArgs);
    }

    public void store(OutputStream out) throws IOException {
        toProperties().store(out, "Qemu Android VM");
    }

    public static VmConfig load(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        VmConfig c = new VmConfig();
        c.fromProperties(p);
        return c;
    }

    static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---- Templates ---------------------------------------------------------

    public static final String[] TEMPLATE_NAMES = {
        "Linux ARM64 (UEFI, ekranlı)",
        "Linux ARM64 (sadece seri konsol)",
        "x86_64 PC (Linux, BIOS)",
        "Windows 7 / Vista (q35, IDE, e1000)",
        "Windows XP / 2000 / 98 (pc, IDE, Cirrus, RTL8139)",
        "x86_64 PC (UEFI)",
        "Boş (elle ayarla)",
    };

    public static VmConfig template(int which) {
        VmConfig c = new VmConfig();
        switch (which) {
            case 0:
                c.name = "Linux ARM64";
                break;
            case 1:
                c.name = "Linux ARM64 konsol";
                c.display = "serial";
                c.gpu = "none";
                c.usbInput = false;
                break;
            case 2:
                c.name = "x86_64 Linux";
                c.arch = ARCH_X86_64;
                c.applyArchDefaults();
                break;
            case 3:
                // Windows 7 has AHCI, e1000, UHCI/EHCI drivers - but no xHCI
                c.name = "Windows 7";
                c.arch = ARCH_X86_64;
                c.applyArchDefaults();
                c.diskIf = "ide";
                c.nic = "e1000";
                c.input = "legacy";
                c.memMb = 2048;
                c.bootFrom = "cdrom";
                break;
            case 4:
                // XP/2000 do not understand q35's ACPI (STOP 0xA5): use i440FX "pc"
                c.name = "Windows XP";
                c.arch = ARCH_X86_64;
                c.applyArchDefaults();
                c.machine = "pc";
                c.cpu = "qemu32";
                c.smp = 1;
                c.memMb = 512;
                c.diskIf = "ide";
                c.gpu = "cirrus-vga";
                c.nic = "rtl8139";
                c.input = "legacy";
                c.tcgThread = "default";
                c.bootFrom = "cdrom";
                break;
            case 5:
                c.name = "x86_64 UEFI";
                c.arch = ARCH_X86_64;
                c.applyArchDefaults();
                c.firmware = "uefi";
                break;
            default:
                c.name = "Yeni VM";
                break;
        }
        return c;
    }
}
