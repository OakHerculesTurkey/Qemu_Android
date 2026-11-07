package com.oakherculesturkey.qemu.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns a {@link VmConfig} into a QEMU command line. */
public final class CommandBuilder {

    /** Filesystem locations the command refers to. */
    public static final class Paths {
        /** Directory holding libqemu-system-*.so (the app's nativeLibraryDir). */
        public File binDir;
        /** QEMU data directory (firmware, keymaps) - passed with -L. */
        public File dataDir;
        /** Per-VM directory: sockets, logs. */
        public File vmDir;

        public File vncSocket() { return new File(vmDir, "vnc.sock"); }
        public File qmpSocket() { return new File(vmDir, "qmp.sock"); }
        public File serialSocket() { return new File(vmDir, "serial.sock"); }
        public File serialLog() { return new File(vmDir, "serial.log"); }
        public File qemuLog() { return new File(vmDir, "qemu.log"); }
    }

    private CommandBuilder() {}

    public static String systemBinaryName(VmConfig c) {
        return "libqemu-system-" + (c.isAarch64() ? "aarch64" : "x86_64") + ".so";
    }

    public static String imageFormat(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".qcow2") || p.endsWith(".qcow")) return "qcow2";
        return "raw";
    }

    public static List<String> build(VmConfig c, Paths p) {
        List<String> a = new ArrayList<>();
        boolean arm = c.isAarch64();
        a.add(new File(p.binDir, systemBinaryName(c)).getAbsolutePath());
        a.add("-L");
        a.add(p.dataDir.getAbsolutePath());
        a.add("-name");
        a.add(c.name.replace(",", ",,"));

        a.add("-machine");
        a.add(c.machine.isEmpty() ? (arm ? "virt" : "q35") : c.machine);

        StringBuilder accel = new StringBuilder(c.accel.isEmpty() ? "tcg" : c.accel);
        if ("tcg".equals(c.accel)) {
            if ("multi".equals(c.tcgThread) || "single".equals(c.tcgThread)) {
                accel.append(",thread=").append(c.tcgThread);
            }
            if (c.tbSizeMb > 0) accel.append(",tb-size=").append(c.tbSizeMb);
        }
        a.add("-accel");
        a.add(accel.toString());

        if (!c.cpu.isEmpty()) {
            a.add("-cpu");
            a.add(c.cpu);
        }
        a.add("-smp");
        a.add(Integer.toString(Math.max(1, c.smp)));
        a.add("-m");
        a.add(Math.max(64, c.memMb) + "M");

        // Firmware
        if ("uefi".equals(c.firmware)) {
            a.add("-bios");
            a.add(new File(p.dataDir, arm ? "edk2-aarch64-code.fd" : "edk2-x86_64-code.fd")
                    .getAbsolutePath());
        }

        // Direct kernel boot
        if (!c.kernel.isEmpty()) {
            a.add("-kernel");
            a.add(c.kernel);
            if (!c.initrd.isEmpty()) {
                a.add("-initrd");
                a.add(c.initrd);
            }
            if (!c.append.isEmpty()) {
                a.add("-append");
                a.add(c.append);
            }
        }

        boolean vnc = "vnc".equals(c.display);
        // ARM's virt board has no PS/2, so only USB 3 input works there
        String input = arm ? "xhci" : c.input;
        boolean wantInput = vnc && c.usbInput;
        boolean usb3Input = wantInput && "xhci".equals(input);
        boolean needXhci = usb3Input || "usb".equals(c.diskIf);
        if (needXhci) {
            a.add("-device");
            a.add("qemu-xhci,id=xhci");
        }
        if (usb3Input) {
            a.add("-device");
            a.add("usb-kbd,bus=xhci.0");
            a.add("-device");
            a.add("usb-tablet,bus=xhci.0");
        } else if (wantInput && "legacy".equals(input)) {
            // PS/2 keyboard (always present on PC machines) + USB 1.1 tablet
            a.add("-device");
            a.add("piix3-usb-uhci,id=uhci");
            a.add("-device");
            a.add("usb-tablet,bus=uhci.0");
        }

        // Display
        if (vnc) {
            if (arm) {
                if (!"none".equals(c.gpu) && !"std".equals(c.gpu)) {
                    a.add("-device");
                    a.add(c.gpu);
                }
            } else {
                if ("std".equals(c.gpu) || c.gpu.isEmpty()) {
                    a.add("-vga");
                    a.add("std");
                } else {
                    a.add("-vga");
                    a.add("none");
                    if (!"none".equals(c.gpu)) {
                        a.add("-device");
                        a.add(c.gpu);
                    }
                }
            }
            a.add("-vnc");
            a.add("unix:" + p.vncSocket().getAbsolutePath());
        } else {
            a.add("-display");
            a.add("none");
            if (!arm) {
                a.add("-vga");
                a.add("none");
            }
        }

        // Serial console (socket for the app + log file), QMP control socket
        a.add("-chardev");
        a.add("socket,id=ser0,path=" + p.serialSocket().getAbsolutePath()
                + ",server=on,wait=off,logfile=" + p.serialLog().getAbsolutePath()
                + ",logappend=off");
        a.add("-serial");
        a.add("chardev:ser0");
        a.add("-qmp");
        a.add("unix:" + p.qmpSocket().getAbsolutePath() + ",server=on,wait=off");
        a.add("-monitor");
        a.add("none");
        if (!arm) {
            a.add("-parallel");
            a.add("none");
        }

        boolean cdFirst = "cdrom".equals(c.bootFrom) && !c.cdrom.isEmpty();
        int diskBoot = cdFirst ? 1 : 0;
        int cdBoot = cdFirst ? 0 : 1;

        // Hard disk
        if (!c.disk.isEmpty()) {
            a.add("-drive");
            a.add("file=" + escapeOpt(c.disk) + ",if=none,id=hd0,format=" + imageFormat(c.disk)
                    + ",cache=writeback,discard=unmap");
            a.add("-device");
            switch (c.diskIf) {
                case "nvme":
                    a.add("nvme,drive=hd0,serial=QEMUNVME0001,bootindex=" + diskBoot);
                    break;
                case "ide":
                    a.add((arm ? "virtio-blk-pci,drive=hd0" : "ide-hd,drive=hd0,bus=ide.0")
                            + ",bootindex=" + diskBoot);
                    break;
                case "usb":
                    a.add("usb-storage,bus=xhci.0,drive=hd0,bootindex=" + diskBoot);
                    break;
                default:
                    a.add("virtio-blk-pci,drive=hd0,bootindex=" + diskBoot);
                    break;
            }
        }

        // CD-ROM
        if (!c.cdrom.isEmpty()) {
            a.add("-drive");
            a.add("file=" + escapeOpt(c.cdrom) + ",if=none,id=cd0,media=cdrom,readonly=on,format=raw");
            if (arm) {
                a.add("-device");
                a.add("virtio-scsi-pci,id=scsi0");
                a.add("-device");
                a.add("scsi-cd,drive=cd0,bus=scsi0.0,bootindex=" + cdBoot);
            } else {
                a.add("-device");
                a.add("ide-cd,drive=cd0,bus=ide.1,bootindex=" + cdBoot);
            }
        }

        // Network
        if ("user".equals(c.network)) {
            StringBuilder nd = new StringBuilder("user,id=net0");
            for (String rule : c.hostfwd.split(",")) {
                rule = rule.trim();
                if (!rule.isEmpty()) nd.append(",hostfwd=").append(rule);
            }
            a.add("-netdev");
            a.add(nd.toString());
            a.add("-device");
            a.add((c.nic.isEmpty() ? "virtio-net-pci" : c.nic) + ",netdev=net0");
        } else {
            a.add("-nic");
            a.add("none");
        }

        a.addAll(Shell.split(c.extraArgs));
        return a;
    }

    /** QEMU option values use ',' as separator; a literal comma is written ",,". */
    static String escapeOpt(String v) {
        return v.replace(",", ",,");
    }
}
