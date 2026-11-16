package com.example.pdfunlock;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Reads the cell text of classic Excel 97-2003 (.xls, BIFF8) workbooks. */
final class XlsReader {
    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private final byte[] wb;
    private final ArrayList<String> sst = new ArrayList<String>();
    private final ArrayList<Integer> xfFmt = new ArrayList<Integer>();
    private final HashMap<Integer, String> fmts = new HashMap<Integer, String>();
    private final HashMap<Integer, Boolean> dateCache = new HashMap<Integer, Boolean>();
    private boolean is1904 = false;

    private XlsReader(byte[] wb) { this.wb = wb; }

    static List<Office.Sheet> read(byte[] data) throws IOException {
        Ole2 ole = new Ole2(data);
        byte[] wb = ole.stream("Workbook");
        if (wb == null) wb = ole.stream("Book");
        if (wb == null) {
            if (ole.has("EncryptedPackage")) throw new IOException("This Excel file is password-protected");
            throw new IOException("No Excel workbook found in this file");
        }
        try {
            return new XlsReader(wb).parse();
        } catch (RuntimeException e) {
            throw new IOException("This Excel file looks damaged (" + e.getClass().getSimpleName() + ")");
        }
    }

    private List<Office.Sheet> parse() throws IOException {
        ArrayList<Object[]> sheets = new ArrayList<Object[]>();
        int pos = 0;
        boolean first = true;
        while (pos + 4 <= wb.length) {
            int id = Office.u16(wb, pos), len = Office.u16(wb, pos + 2), dp = pos + 4;
            if (dp + len > wb.length) break;
            if (id == 0x809) {
                if (first) {
                    int ver = Office.u16(wb, dp);
                    if (ver != 0 && ver < 0x600) throw new IOException("Excel 5.0/95 files aren’t supported");
                    first = false;
                }
            } else if (id == 0x2F) {
                throw new IOException("This Excel file is password-protected");
            } else if (id == 0x22) {
                is1904 = Office.u16(wb, dp) == 1;
            } else if (id == 0x85) {
                long off = Office.u32(wb, dp);
                int type = wb[dp + 5] & 0xFF;
                int cch = wb[dp + 6] & 0xFF;
                boolean wide = (wb[dp + 7] & 1) != 0;
                String nm = wide ? new String(wb, dp + 8, cch * 2, UTF16) : new String(wb, dp + 8, cch, CP1252);
                if (type == 0) sheets.add(new Object[] { nm, (int) off });
            } else if (id == 0x41) {
                fmts.put(Office.u16(wb, dp), readUStr(wb, dp + 2));
            } else if (id == 0xE0) {
                xfFmt.add(Office.u16(wb, dp + 2));
            } else if (id == 0xFC) {
                ArrayList<byte[]> chunks = new ArrayList<byte[]>();
                chunks.add(Arrays.copyOfRange(wb, dp, dp + len));
                int p2 = dp + len;
                while (p2 + 4 <= wb.length && Office.u16(wb, p2) == 0x3C) {
                    int l2 = Office.u16(wb, p2 + 2);
                    if (p2 + 4 + l2 > wb.length) break;
                    chunks.add(Arrays.copyOfRange(wb, p2 + 4, p2 + 4 + l2));
                    p2 += 4 + l2;
                }
                readSst(chunks);
                pos = p2;
                continue;
            } else if (id == 0x0A) {
                break;
            }
            pos = dp + len;
        }
        ArrayList<Office.Sheet> out = new ArrayList<Office.Sheet>();
        for (Object[] s : sheets) out.add(readSheet((String) s[0], (Integer) s[1]));
        return out;
    }

    private void readSst(ArrayList<byte[]> chunks) {
        int ci = 0, p = 8;
        int unique = (int) Math.min(Office.u32(chunks.get(0), 4), 5000000L);
        try {
            for (int i = 0; i < unique; i++) {
                if (p >= chunks.get(ci).length) {
                    ci++;
                    p = 0;
                    if (ci >= chunks.size()) break;
                }
                byte[] c = chunks.get(ci);
                int cch = Office.u16(c, p);
                int flags = c[p + 2] & 0xFF;
                p += 3;
                int runs = 0;
                long ext = 0;
                if ((flags & 8) != 0) { runs = Office.u16(c, p); p += 2; }
                if ((flags & 4) != 0) { ext = Office.u32(c, p); p += 4; }
                boolean wide = (flags & 1) != 0;
                StringBuilder sb = new StringBuilder(cch);
                int remaining = cch;
                while (remaining > 0) {
                    c = chunks.get(ci);
                    if (p >= c.length) {
                        ci++;
                        if (ci >= chunks.size()) break;
                        c = chunks.get(ci);
                        wide = (c[0] & 1) != 0;
                        p = 1;
                    }
                    int avail = c.length - p;
                    int chars = wide ? avail / 2 : avail;
                    int take = Math.min(remaining, chars);
                    if (take <= 0) { p = c.length; continue; }
                    if (wide) sb.append(new String(c, p, take * 2, UTF16));
                    else sb.append(new String(c, p, take, CP1252));
                    p += wide ? take * 2 : take;
                    remaining -= take;
                }
                long skip = runs * 4L + ext;
                while (skip > 0 && ci < chunks.size()) {
                    c = chunks.get(ci);
                    int avail = c.length - p;
                    if (avail <= 0) { ci++; p = 0; continue; }
                    int t = (int) Math.min(avail, skip);
                    p += t;
                    skip -= t;
                }
                sst.add(sb.toString());
            }
        } catch (RuntimeException e) {
            // keep the strings read so far
        }
    }

    private Office.Sheet readSheet(String name, int off) {
        Office.Sheet sh = new Office.Sheet();
        sh.name = name;
        int pos = off, depth = 0, pendRow = -1, pendCol = -1;
        while (pos + 4 <= wb.length) {
            int id = Office.u16(wb, pos), len = Office.u16(wb, pos + 2), dp = pos + 4;
            if (dp + len > wb.length) break;
            if (id == 0x809) {
                depth++;
            } else if (id == 0x0A) {
                depth--;
                if (depth <= 0) break;
            } else if (depth == 1) {
                switch (id) {
                    case 0xFD: { // LABELSST
                        int isst = (int) Office.u32(wb, dp + 6);
                        if (isst >= 0 && isst < sst.size()) sh.put(Office.u16(wb, dp), Office.u16(wb, dp + 2), sst.get(isst));
                        break;
                    }
                    case 0x204: // LABEL
                        sh.put(Office.u16(wb, dp), Office.u16(wb, dp + 2), readUStr(wb, dp + 6));
                        break;
                    case 0x203: // NUMBER
                        sh.put(Office.u16(wb, dp), Office.u16(wb, dp + 2),
                                fmtNum(Office.u16(wb, dp + 4), Double.longBitsToDouble(readLong(wb, dp + 6))));
                        break;
                    case 0x27E: // RK
                        sh.put(Office.u16(wb, dp), Office.u16(wb, dp + 2),
                                fmtNum(Office.u16(wb, dp + 4), rk(Office.i32(wb, dp + 6))));
                        break;
                    case 0xBD: { // MULRK
                        int row = Office.u16(wb, dp), col = Office.u16(wb, dp + 2);
                        for (int q = dp + 4; q + 6 <= dp + len - 2; q += 6, col++) {
                            sh.put(row, col, fmtNum(Office.u16(wb, q), rk(Office.i32(wb, q + 2))));
                        }
                        break;
                    }
                    case 0x06: { // FORMULA
                        int row = Office.u16(wb, dp), col = Office.u16(wb, dp + 2), xf = Office.u16(wb, dp + 4);
                        if (Office.u16(wb, dp + 12) == 0xFFFF) {
                            int t = wb[dp + 6] & 0xFF;
                            if (t == 0) { pendRow = row; pendCol = col; }
                            else if (t == 1) sh.put(row, col, (wb[dp + 8] & 0xFF) != 0 ? "TRUE" : "FALSE");
                            else if (t == 2) sh.put(row, col, errText(wb[dp + 8] & 0xFF));
                        } else {
                            sh.put(row, col, fmtNum(xf, Double.longBitsToDouble(readLong(wb, dp + 6))));
                        }
                        break;
                    }
                    case 0x207: // STRING (result of the previous formula)
                        if (pendRow >= 0) {
                            sh.put(pendRow, pendCol, readUStr(wb, dp));
                            pendRow = -1;
                        }
                        break;
                    case 0x205: { // BOOLERR
                        int v = wb[dp + 6] & 0xFF;
                        boolean err = (wb[dp + 7] & 0xFF) != 0;
                        sh.put(Office.u16(wb, dp), Office.u16(wb, dp + 2), err ? errText(v) : (v != 0 ? "TRUE" : "FALSE"));
                        break;
                    }
                    default:
                        break;
                }
            }
            pos = dp + len;
        }
        return sh;
    }

    private static long readLong(byte[] b, int o) {
        return (Office.u32(b, o)) | (Office.u32(b, o + 4) << 32);
    }

    private static double rk(int v) {
        double d;
        if ((v & 2) != 0) d = (v >> 2);
        else d = Double.longBitsToDouble((v & 0xFFFFFFFCL) << 32);
        if ((v & 1) != 0) d /= 100;
        return d;
    }

    private static String errText(int code) {
        switch (code) {
            case 0x00: return "#NULL!";
            case 0x07: return "#DIV/0!";
            case 0x0F: return "#VALUE!";
            case 0x17: return "#REF!";
            case 0x1D: return "#NAME?";
            case 0x24: return "#NUM!";
            case 0x2A: return "#N/A";
            default: return "#ERR";
        }
    }

    private String fmtNum(int xf, double d) {
        return isDateXf(xf) ? Office.excelDate(d, is1904) : Office.num(d);
    }

    private boolean isDateXf(int xf) {
        Boolean c = dateCache.get(xf);
        if (c != null) return c;
        boolean r = false;
        if (xf >= 0 && xf < xfFmt.size()) {
            int f = xfFmt.get(xf);
            r = Office.isBuiltinDate(f) || (fmts.containsKey(f) && Office.isDateFormat(fmts.get(f)));
        }
        dateCache.put(xf, r);
        return r;
    }

    /** Unicode string with 16-bit length (LABEL, STRING, FORMAT records). */
    private static String readUStr(byte[] b, int off) {
        int cch = Office.u16(b, off);
        int fl = b[off + 2] & 0xFF;
        int p = off + 3;
        if ((fl & 8) != 0) p += 2;
        if ((fl & 4) != 0) p += 4;
        boolean wide = (fl & 1) != 0;
        int nbytes = wide ? cch * 2 : cch;
        if (p + nbytes > b.length) nbytes = Math.max(0, b.length - p);
        if (wide) nbytes &= ~1;
        return new String(b, p, nbytes, wide ? UTF16 : CP1252);
    }
}
