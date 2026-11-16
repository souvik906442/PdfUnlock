package com.example.pdfunlock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal reader for OLE2 / Compound File containers (old .xls and .ppt files). */
final class Ole2 {
    private static final Charset UTF16 = Charset.forName("UTF-16LE");

    private final byte[] d;
    private final int secSize;
    private final int miniSize;
    private final int cutoff;
    private int[] fat;
    private int[] miniFat = new int[0];
    private byte[] miniStream = new byte[0];
    private final Map<String, int[]> entries = new LinkedHashMap<String, int[]>();

    static boolean isOle(byte[] b) {
        return b.length >= 512 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF
                && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0;
    }

    Ole2(byte[] data) throws IOException {
        d = data;
        if (!isOle(d)) throw new IOException("Not an OLE2 file");
        try {
            secSize = 1 << Office.u16(d, 0x1E);
            miniSize = 1 << Office.u16(d, 0x20);
            int numFat = Office.i32(d, 0x2C);
            int firstDir = Office.i32(d, 0x30);
            cutoff = Office.i32(d, 0x38);
            int firstMini = Office.i32(d, 0x3C);
            int firstDifat = Office.i32(d, 0x44);
            int numDifat = Office.i32(d, 0x48);
            if (secSize < 128 || secSize > 65536 || miniSize < 8 || miniSize > 4096) {
                throw new IOException("Unsupported file layout");
            }

            ArrayList<Integer> fatSecs = new ArrayList<Integer>();
            for (int i = 0; i < 109 && fatSecs.size() < numFat; i++) fatSecs.add(Office.i32(d, 0x4C + 4 * i));
            int dsec = firstDifat;
            for (int k = 0; k < numDifat && dsec >= 0 && fatSecs.size() < numFat; k++) {
                long off = ((long) dsec + 1) * secSize;
                if (off + secSize > d.length) break;
                int per = secSize / 4 - 1;
                for (int i = 0; i < per && fatSecs.size() < numFat; i++) fatSecs.add(Office.i32(d, (int) off + 4 * i));
                dsec = Office.i32(d, (int) off + 4 * per);
            }
            int per = secSize / 4;
            fat = new int[fatSecs.size() * per];
            for (int s = 0; s < fatSecs.size(); s++) {
                long off = ((long) fatSecs.get(s) + 1) * secSize;
                for (int i = 0; i < per; i++) {
                    long p = off + 4L * i;
                    fat[s * per + i] = (fatSecs.get(s) >= 0 && p + 4 <= d.length) ? Office.i32(d, (int) p) : -1;
                }
            }

            byte[] dir = chain(firstDir);
            int rootStart = -1, rootSize = 0;
            for (int o = 0; o + 128 <= dir.length; o += 128) {
                int type = dir[o + 66] & 0xFF;
                if (type == 0) continue;
                int nameLen = Office.u16(dir, o + 64);
                String name = new String(dir, o, Math.max(0, Math.min(nameLen, 64) - 2), UTF16);
                int start = Office.i32(dir, o + 116);
                int size = Office.i32(dir, o + 120);
                if (size < 0) size = Integer.MAX_VALUE;
                if (type == 5 && rootStart == -1) { rootStart = start; rootSize = size; }
                if (!entries.containsKey(name)) entries.put(name, new int[] { start, size, type });
            }
            if (rootStart >= 0) {
                byte[] ms = chain(rootStart);
                miniStream = ms.length > rootSize ? java.util.Arrays.copyOf(ms, rootSize) : ms;
            }
            byte[] mf = chain(firstMini);
            miniFat = new int[mf.length / 4];
            for (int i = 0; i < miniFat.length; i++) miniFat[i] = Office.i32(mf, 4 * i);
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("This file looks damaged");
        }
    }

    boolean has(String name) { return entries.containsKey(name); }

    byte[] stream(String name) {
        int[] e = entries.get(name);
        if (e == null || e[2] != 2) return null;
        int size = e[1];
        byte[] raw = size < cutoff ? miniChain(e[0]) : chain(e[0]);
        return raw.length > size ? java.util.Arrays.copyOf(raw, size) : raw;
    }

    private byte[] chain(int start) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int sid = start, guard = 0;
        while (sid >= 0 && sid < fat.length && guard++ <= fat.length) {
            long off = ((long) sid + 1) * secSize;
            if (off >= d.length) break;
            int n = (int) Math.min(secSize, d.length - off);
            o.write(d, (int) off, n);
            sid = fat[sid];
        }
        return o.toByteArray();
    }

    private byte[] miniChain(int start) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int sid = start, guard = 0;
        while (sid >= 0 && sid < miniFat.length && guard++ <= miniFat.length) {
            long off = (long) sid * miniSize;
            if (off >= miniStream.length) break;
            int n = (int) Math.min(miniSize, miniStream.length - off);
            o.write(miniStream, (int) off, n);
            sid = miniFat[sid];
        }
        return o.toByteArray();
    }
}
