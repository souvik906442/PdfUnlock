package com.example.pdfunlock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Reads the cell text of .xlsx workbooks. */
final class XlsxReader {
    private XlsxReader() {}

    static List<Office.Sheet> read(byte[] data) throws Exception {
        Map<String, byte[]> z = Office.readZip(data, "xl/");
        byte[] wbXml = z.get("xl/workbook.xml");
        if (wbXml == null) throw new IOException("This Excel file is protected or is not a valid .xlsx");

        WorkbookHandler wh = new WorkbookHandler();
        Office.sax(wbXml, wh);

        Map<String, String> rels = new HashMap<String, String>();
        byte[] relXml = z.get("xl/_rels/workbook.xml.rels");
        if (relXml != null) {
            Office.sax(relXml, new DefaultHandler() {
                @Override
                public void startElement(String uri, String ln, String q, Attributes a) {
                    if (q.equals("Relationship")) rels.put(a.getValue("Id"), a.getValue("Target"));
                }
            });
        }

        SstHandler sh = new SstHandler();
        byte[] sstXml = z.get("xl/sharedStrings.xml");
        if (sstXml != null) Office.sax(sstXml, sh);

        StylesHandler st = new StylesHandler();
        byte[] styXml = z.get("xl/styles.xml");
        if (styXml != null) Office.sax(styXml, st);
        boolean[] dateXf = st.dateFlags();

        ArrayList<Office.Sheet> out = new ArrayList<Office.Sheet>();
        for (int i = 0; i < wh.names.size(); i++) {
            String target = rels.get(wh.rids.get(i));
            if (target == null) continue;
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            byte[] sheetXml = z.get(path);
            if (sheetXml == null || path.contains("chartsheets")) continue;
            Office.Sheet sheet = new Office.Sheet();
            sheet.name = wh.names.get(i);
            Office.sax(sheetXml, new SheetHandler(sheet, sh.list, dateXf, wh.is1904));
            out.add(sheet);
        }
        return out;
    }

    private static final class WorkbookHandler extends DefaultHandler {
        final ArrayList<String> names = new ArrayList<String>();
        final ArrayList<String> rids = new ArrayList<String>();
        boolean is1904 = false;

        @Override
        public void startElement(String uri, String ln, String q, Attributes a) {
            if (q.equals("workbookPr")) {
                String v = a.getValue("date1904");
                is1904 = "1".equals(v) || "true".equals(v);
            } else if (q.equals("sheet")) {
                names.add(a.getValue("name") == null ? "Sheet" : a.getValue("name"));
                rids.add(Office.relId(a));
            }
        }
    }

    private static final class SstHandler extends DefaultHandler {
        final ArrayList<String> list = new ArrayList<String>();
        private StringBuilder cur = new StringBuilder();
        private boolean inT = false, inPh = false;

        @Override
        public void startElement(String uri, String ln, String q, Attributes a) {
            if (q.equals("si")) cur = new StringBuilder();
            else if (q.equals("rPh")) inPh = true;
            else if (q.equals("t")) inT = true;
        }

        @Override
        public void characters(char[] ch, int s, int l) {
            if (inT && !inPh) cur.append(ch, s, l);
        }

        @Override
        public void endElement(String uri, String ln, String q) {
            if (q.equals("t")) inT = false;
            else if (q.equals("rPh")) inPh = false;
            else if (q.equals("si")) list.add(cur.toString());
        }
    }

    private static final class StylesHandler extends DefaultHandler {
        private final HashMap<Integer, String> custom = new HashMap<Integer, String>();
        private final ArrayList<Integer> xfs = new ArrayList<Integer>();
        private boolean inCellXfs = false;

        @Override
        public void startElement(String uri, String ln, String q, Attributes a) {
            if (q.equals("numFmt")) {
                try {
                    custom.put(Integer.parseInt(a.getValue("numFmtId")), a.getValue("formatCode"));
                } catch (RuntimeException ignored) { }
            } else if (q.equals("cellXfs")) {
                inCellXfs = true;
            } else if (q.equals("xf") && inCellXfs) {
                int id = 0;
                try { id = Integer.parseInt(a.getValue("numFmtId")); } catch (RuntimeException ignored) { }
                xfs.add(id);
            }
        }

        @Override
        public void endElement(String uri, String ln, String q) {
            if (q.equals("cellXfs")) inCellXfs = false;
        }

        boolean[] dateFlags() {
            boolean[] r = new boolean[xfs.size()];
            for (int i = 0; i < r.length; i++) {
                int f = xfs.get(i);
                r[i] = Office.isBuiltinDate(f) || (custom.containsKey(f) && Office.isDateFormat(custom.get(f)));
            }
            return r;
        }
    }

    private static final class SheetHandler extends DefaultHandler {
        private final Office.Sheet sheet;
        private final List<String> sst;
        private final boolean[] dateXf;
        private final boolean is1904;
        private int row = -1, col = -1, curRow = 0, curCol = 0;
        private String type, ref;
        private int style;
        private boolean inV = false, inT = false;
        private final StringBuilder buf = new StringBuilder();

        SheetHandler(Office.Sheet sheet, List<String> sst, boolean[] dateXf, boolean is1904) {
            this.sheet = sheet;
            this.sst = sst;
            this.dateXf = dateXf;
            this.is1904 = is1904;
        }

        @Override
        public void startElement(String uri, String ln, String q, Attributes a) {
            if (q.equals("row")) {
                String r = a.getValue("r");
                row = r != null ? parseInt(r, row + 2) - 1 : row + 1;
                col = -1;
            } else if (q.equals("c")) {
                ref = a.getValue("r");
                curRow = row;
                curCol = col + 1;
                if (ref != null) {
                    int i = 0, c = 0;
                    while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
                        c = c * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
                        i++;
                    }
                    if (c > 0) curCol = c - 1;
                    if (i < ref.length()) curRow = parseInt(ref.substring(i), row + 1) - 1;
                }
                col = curCol;
                type = a.getValue("t");
                style = parseInt(a.getValue("s"), 0);
                buf.setLength(0);
            } else if (q.equals("v")) {
                inV = true;
            } else if (q.equals("t")) {
                inT = true;
            }
        }

        @Override
        public void characters(char[] ch, int s, int l) {
            if (inV || inT) buf.append(ch, s, l);
        }

        @Override
        public void endElement(String uri, String ln, String q) {
            if (q.equals("v")) {
                inV = false;
            } else if (q.equals("t")) {
                inT = false;
            } else if (q.equals("c")) {
                String v = buf.toString();
                String text;
                if (type == null || type.equals("n")) {
                    text = v;
                    if (!v.isEmpty()) {
                        try {
                            double d = Double.parseDouble(v.trim());
                            text = (style >= 0 && style < dateXf.length && dateXf[style])
                                    ? Office.excelDate(d, is1904) : Office.num(d);
                        } catch (NumberFormatException ignored) { }
                    }
                } else if (type.equals("s")) {
                    int i = parseInt(v.trim(), -1);
                    text = (i >= 0 && i < sst.size()) ? sst.get(i) : "";
                } else if (type.equals("b")) {
                    text = v.trim().equals("1") ? "TRUE" : "FALSE";
                } else {
                    text = v;
                }
                sheet.put(curRow, curCol, text);
            }
        }

        private static int parseInt(String s, int def) {
            if (s == null) return def;
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
        }
    }
}
