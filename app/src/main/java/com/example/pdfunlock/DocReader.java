package com.example.pdfunlock;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Reads the text of classic Word 97-2003 (.doc) files, paragraph by paragraph. */
final class DocReader {
    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private DocReader() {}

    static List<String> read(byte[] data) throws IOException {
        Ole2 ole = new Ole2(data);
        byte[] wd = ole.stream("WordDocument");
        if (wd == null || wd.length < 0x1B0) {
            if (ole.has("EncryptedPackage")) throw new IOException("This Word file is password-protected");
            throw new IOException("No Word document data found in this file");
        }
        try {
            return parse(ole, wd);
        } catch (RuntimeException e) {
            throw new IOException("This Word file looks damaged");
        }
    }

    private static List<String> parse(Ole2 ole, byte[] wd) throws IOException {
        if (Office.u16(wd, 0) != 0xA5EC) throw new IOException("This doesn’t look like a Word file");
        int nFib = Office.u16(wd, 2);
        if (nFib < 0xC1) throw new IOException("Word 6/95 files aren’t supported");
        int flags = Office.u16(wd, 0x0A);
        if ((flags & 0x0100) != 0) throw new IOException("This Word file is password-protected");
        byte[] table = ole.stream((flags & 0x0200) != 0 ? "1Table" : "0Table");
        if (table == null) throw new IOException("This Word file looks damaged");

        long ccpText = Office.u32(wd, 0x4C);
        long fcClx = Office.u32(wd, 0x1A2);
        long lcbClx = Office.u32(wd, 0x1A6);
        if (fcClx + lcbClx > table.length) throw new IOException("This Word file looks damaged");

        int pos = (int) fcClx, end = (int) (fcClx + lcbClx), plc = -1, lcb = 0;
        while (pos < end) {
            int b = table[pos] & 0xFF;
            if (b == 1) {
                pos += 3 + Office.u16(table, pos + 1);
            } else if (b == 2) {
                lcb = (int) Office.u32(table, pos + 1);
                plc = pos + 5;
                break;
            } else {
                break;
            }
        }
        if (plc < 0) throw new IOException("This Word file looks damaged");
        int n = (lcb - 4) / 12;

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long cpStart = Office.u32(table, plc + 4 * i);
            long cpEnd = Office.u32(table, plc + 4 * (i + 1));
            if (ccpText > 0 && cpStart >= ccpText) break;
            if (ccpText > 0 && cpEnd > ccpText) cpEnd = ccpText;
            long chars = cpEnd - cpStart;
            if (chars <= 0) continue;
            long fc = Office.u32(table, plc + 4 * (n + 1) + 8 * i + 2);
            boolean compressed = (fc & 0x40000000L) != 0;
            long off = compressed ? (fc & 0x3FFFFFFFL) / 2 : fc;
            long len = compressed ? chars : chars * 2;
            if (off < 0 || off + len > wd.length) len = Math.max(0, wd.length - off);
            if (len <= 0) continue;
            text.append(new String(wd, (int) off, (int) (compressed ? len : (len & ~1L)), compressed ? CP1252 : UTF16));
        }
        return paragraphs(text.toString());
    }

    private static List<String> paragraphs(String raw) {
        // drop field instructions (between 0x13 and 0x14) but keep their visible result
        StringBuilder s = new StringBuilder(raw.length());
        int depth = 0;
        boolean[] inCode = new boolean[64];
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 0x13) { if (depth < 63) depth++; inCode[depth] = true; continue; }
            if (c == 0x14) { if (depth > 0) inCode[depth] = false; continue; }
            if (c == 0x15) { if (depth > 0) { inCode[depth] = false; depth--; } continue; }
            if (depth > 0 && inCode[depth]) continue;
            s.append(c);
        }
        ArrayList<String> out = new ArrayList<String>();
        int empties = 0;
        for (String p : s.toString().split("\r", -1)) {
            StringBuilder q = new StringBuilder();
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c == 0x07) q.append("  |  ");
                else if (c == 0x0B) q.append('\n');
                else if (c == 0x1E) q.append('-');
                else if (c == '\t' || c == '\n' || c >= 0x20) q.append(c);
            }
            String line = q.toString().replaceAll("(\\s*\\|\\s*)+$", "").replace("﻿", "");
            if (line.trim().isEmpty()) {
                if (++empties > 1) continue;
                line = "";
            } else {
                empties = 0;
            }
            out.add(line);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }
}
