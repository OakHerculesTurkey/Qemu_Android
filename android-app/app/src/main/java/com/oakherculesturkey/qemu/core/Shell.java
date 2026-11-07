package com.oakherculesturkey.qemu.core;

import java.util.ArrayList;
import java.util.List;

/** Minimal POSIX-shell-like word splitting for the "extra arguments" field. */
public final class Shell {
    private Shell() {}

    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else if (ch == '\\' && quote == '"' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(ch);
                }
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                inWord = true;
            } else if (ch == '\\' && i + 1 < s.length()) {
                cur.append(s.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(ch)) {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
            } else {
                cur.append(ch);
                inWord = true;
            }
        }
        if (inWord) out.add(cur.toString());
        return out;
    }

    /** Quotes a word for display so the command can be copy-pasted. */
    public static String quote(String w) {
        if (!w.isEmpty() && w.matches("[A-Za-z0-9_./:=,+@%-]+")) return w;
        return "'" + w.replace("'", "'\\''") + "'";
    }

    public static String join(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(quote(a));
        }
        return sb.toString();
    }
}
