package com.example.pdfunlock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Entry points + shared helpers for reading Excel (.xls/.xlsx) and PowerPoint (.ppt/.pptx) files. */
public final class Office {
    private Office() {}

    public static final int MAX_ROWS = 20000;
    public static final int MAX_COLS = 100;
    static final int MAX_BYTES = 80 * 1024 * 1024;

    public static class Sheet {
        public String name = "";
        public final ArrayList<ArrayList<String>> rows = new ArrayList<ArrayList<String>>();
        public boolean truncated = false;

        void put(int r, int c, String v) {
            if (v == null || v.isEmpty() || r < 0 || c < 0) return;
            if (r >= MAX_ROWS || c >= MAX_COLS) { truncated = true; return; }
            while (rows.size() <= r) rows.add(new ArrayList<String>());
            ArrayList<String> row = rows.get(r);
            while (row.size() <= c) row.add("");
            row.set(c, v);
        }
    }

    public static class Slide {
        public String title = null;
        public final ArrayList<String> body = new ArrayList<String>();
    }

    public static List<Sheet> readSheets(InputStream in) throws Exception {
        byte[] data = readAll(in);
        List<Sheet> r;
        if (isZip(data)) r = XlsxReader.read(data);
        else if (Ole2.isOle(data)) r = XlsReader.read(data);
        else throw new IOException("This doesn’t look like an Excel file");
        if (r.isEmpty()) throw new IOException("No sheets found in this file");
        return r;
    }

    public static List<Slide> readSlides(InputStream in) throws Exception {
        byte[] data = readAll(in);
        List<Slide> r;
        if (isZip(data)) r = PptxReader.read(data);
        else if (Ole2.isOle(data)) r = PptReader.read(data);
        else throw new IOException("This doesn’t look like a PowerPoint file");
        if (r.isEmpty()) throw new IOException("No slides found in this file");
        return r;
    }

    public static boolean isOleBytes(byte[] b) { return Ole2.isOle(b); }

    /** Paragraphs of a classic .doc file. */
    public static List<String> readDoc(byte[] data) throws Exception {
        return DocReader.read(data);
    }

    // ---------- helpers ----------

    static boolean isZip(byte[] b) { return b.length > 4 && b[0] == 'P' && b[1] == 'K'; }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    static long u32(byte[] b, int o) { return i32(b, o) & 0xFFFFFFFFL; }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        long total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BYTES) throw new IOException("File is too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Reads only the .xml / .rels entries whose names start with one of the prefixes. */
    static Map<String, byte[]> readZip(byte[] data, String... prefixes) throws IOException {
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry e;
            long total = 0;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                boolean want = false;
                for (String p : prefixes) if (name.startsWith(p)) { want = true; break; }
                if (!want || !(name.endsWith(".xml") || name.endsWith(".rels"))) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = zin.read(buf)) != -1) {
                    total += n;
                    if (total > 2L * MAX_BYTES) throw new IOException("File is too large");
                    bo.write(buf, 0, n);
                }
                out.put(name, bo.toByteArray());
            }
        } finally {
            zin.close();
        }
        return out;
    }

    static void sax(byte[] xml, org.xml.sax.helpers.DefaultHandler h) throws Exception {
        javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        f.newSAXParser().parse(new ByteArrayInputStream(xml), h);
    }

    static String attr(org.xml.sax.Attributes a, String name) {
        String v = a.getValue(name);
        return v;
    }

    /** Attribute like r:id whatever prefix the file uses. */
    static String relId(org.xml.sax.Attributes a) {
        for (int i = 0; i < a.getLength(); i++) {
            String q = a.getQName(i);
            if (q.equals("r:id") || q.endsWith(":id")) return a.getValue(i);
        }
        return null;
    }

    static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new BigDecimal(d).round(new MathContext(15)).stripTrailingZeros().toPlainString();
    }

    static String excelDate(double serial, boolean is1904) {
        if (serial < 0 || Double.isNaN(serial) || serial > 2958465) return num(serial);
        SimpleDateFormat f;
        long secs;
        if (serial < 1) {
            f = new SimpleDateFormat("HH:mm:ss", Locale.US);
            secs = Math.round(serial * 86400.0);
        } else {
            double base = is1904 ? 24107 : 25569;
            secs = Math.round((serial - base) * 86400.0);
            boolean hasTime = Math.abs(serial - Math.floor(serial)) > 1e-6;
            f = new SimpleDateFormat(hasTime ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd", Locale.US);
        }
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(secs * 1000L));
    }

    /** Heuristic: does this number format code show a date/time? */
    static boolean isDateFormat(String code) {
        if (code == null) return false;
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false, inBracket = false;
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '"') { inQuote = !inQuote; continue; }
            if (inQuote) continue;
            if (ch == '[') { inBracket = true; continue; }
            if (ch == ']') { inBracket = false; continue; }
            if (inBracket) continue;
            if (ch == '\\' || ch == '_' || ch == '*') { i++; continue; }
            sb.append(Character.toLowerCase(ch));
        }
        String s = sb.toString();
        if (s.startsWith("general")) return false;
        return s.indexOf('y') >= 0 || s.indexOf('d') >= 0 || s.indexOf('h') >= 0
                || s.indexOf('s') >= 0 || s.indexOf('m') >= 0;
    }

    static boolean isBuiltinDate(int id) {
        return (id >= 14 && id <= 22) || (id >= 45 && id <= 47);
    }
}
