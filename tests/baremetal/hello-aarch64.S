// Minimal bare-metal guest for `qemu-system-aarch64 -M virt`:
// prints a message on the PL011 UART and exits QEMU through semihosting.
    .section .text
    .global _start
_start:
    ldr     x1, =0x09000000         // PL011 UART data register (virt board)
    adr     x2, msg
1:  ldrb    w3, [x2], #1
    cbz     w3, 2f
    strb    w3, [x1]
    b       1b
2:  // SYS_EXIT (0x18) with ADP_Stopped_ApplicationExit -> QEMU exits, status 0
    mov     x0, #0x18
    ldr     x1, =exit_block
    hlt     #0xf000
3:  b       3b

    .section .rodata
msg:        .asciz "Hello from an aarch64 guest running in QEMU on Android!\n"
    .balign 8
exit_block: .quad 0x20026, 0
