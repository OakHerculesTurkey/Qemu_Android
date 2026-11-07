package com.oakherculesturkey.qemu.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One-shot QMP (QEMU Machine Protocol) exchange: greeting, capabilities
 * negotiation, one command. QEMU's QMP socket serves one client at a time, so
 * every call uses a fresh connection.
 */
public final class QmpClient {
    private QmpClient() {}

    /** Executes a raw JSON command and returns the raw JSON reply line. */
    public static String execute(InputStream in, OutputStream out, String json) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String greeting = r.readLine();
        if (greeting == null || !greeting.contains("QMP")) {
            throw new IOException("No QMP greeting: " + greeting);
        }
        send(out, "{\"execute\":\"qmp_capabilities\"}");
        readReply(r);
        send(out, json);
        return readReply(r);
    }

    public static String command(String name) {
        return "{\"execute\":\"" + name + "\"}";
    }

    public static String hmp(String commandLine) {
        return "{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":"
                + jsonString(commandLine) + "}}";
    }

    private static void send(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readReply(BufferedReader r) throws IOException {
        while (true) {
            String line = r.readLine();
            if (line == null) throw new IOException("QMP connection closed");
            if (line.contains("\"return\"") || line.contains("\"error\"")) return line;
            // asynchronous {"event": ...} lines are skipped
        }
    }

    /** Human readable form of a reply: the HMP text, the error, or the JSON. */
    public static String describe(String reply) {
        int e = reply.indexOf("\"desc\"");
        if (reply.contains("\"error\"") && e >= 0) {
            String d = stringAt(reply, reply.indexOf(':', e) + 1);
            return "Hata: " + (d != null ? d : reply);
        }
        int ret = reply.indexOf("\"return\"");
        if (ret >= 0) {
            int colon = reply.indexOf(':', ret) + 1;
            String s = stringAt(reply, colon);
            if (s != null) return s;
            String rest = reply.substring(colon).trim();
            if (rest.startsWith("{}")) return "OK";
            return rest.endsWith("}") ? rest.substring(0, rest.length() - 1) : rest;
        }
        return reply;
    }

    /** Parses a JSON string literal starting at (after whitespace) index i. */
    static String stringAt(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '"') return null;
        StringBuilder sb = new StringBuilder();
        for (i++; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') return sb.toString();
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
