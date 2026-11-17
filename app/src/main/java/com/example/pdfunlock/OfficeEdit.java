package com.example.pdfunlock;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Edits .docx and .xlsx files in place at the XML level.
 * Everything that is not touched (images, styles, formulas, formatting, macros...) is copied back unchanged.
 */
public final class OfficeEdit {
    private OfficeEdit() {}

    static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    // ------------------------------------------------------------------ helpers

    static LinkedHashMap<String, byte[]> readZip(byte[] data) throws IOException {
        LinkedHashMap<String, byte[]> map = new LinkedHashMap<String, byte[]>();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            byte[] buf = new byte[65536];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                map.put(e.getName(), bo.toByteArray());
            }
        } finally {
            zin.close();
        }
        return map;
    }

    static byte[] writeZip(Map<String, byte[]> map) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream zo = new ZipOutputStream(bo);
        for (Map.Entry<String, byte[]> en : map.entrySet()) {
            zo.putNextEntry(new ZipEntry(en.getKey()));
            zo.write(en.getValue());
            zo.closeEntry();
        }
        zo.close();
        return bo.toByteArray();
    }

    static Document parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }

    static byte[] toBytes(Document d) throws Exception {
        d.setXmlStandalone(true);
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        t.transform(new DOMSource(d), new StreamResult(bo));
        return bo.toByteArray();
    }

    static Element child(Element parent, String local) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && local.equals(c.getLocalName())) return (Element) c;
        }
        return null;
    }

    static Element mk(Element parent, String local) {
        String pre = parent.getPrefix();
        return parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(),
                pre == null ? local : pre + ":" + local);
    }

    static void setPreserve(Element e) {
        e.setAttributeNS(XML_NS, "xml:space", "preserve");
    }

    // ------------------------------------------------------------------ DOCX

    public static final class DocxEditor {
        private static final class Seg {
            final Element el;
            final boolean isText;
            String text;

            Seg(Element el, boolean isText, String text) {
                this.el = el;
                this.isText = isText;
                this.text = text;
            }

            int len() { return isText ? text.length() : 1; }

            String display() {
                if (isText) return text;
                return "tab".equals(el.getLocalName()) ? "\t" : "\n";
            }
        }

        private final LinkedHashMap<String, byte[]> entries;
        private final Document doc;
        private final List<List<Seg>> paras = new ArrayList<List<Seg>>();
        private final List<String> texts = new ArrayList<String>();

        public DocxEditor(byte[] data) throws Exception {
            entries = readZip(data);
            byte[] x = entries.get("word/document.xml");
            if (x == null) throw new IllegalArgumentException("Not a valid .docx (no word/document.xml)");
            doc = parseXml(x);
            NodeList ps = doc.getElementsByTagNameNS(W_NS, "p");
            for (int i = 0; i < ps.getLength(); i++) {
                Element p = (Element) ps.item(i);
                if (hasParagraphAncestor(p)) continue;
                List<Seg> segs = new ArrayList<Seg>();
                collect(p, segs);
                boolean any = false;
                for (Seg s : segs) if (s.isText && !s.text.trim().isEmpty()) { any = true; break; }
                if (!any) continue;
                paras.add(segs);
                texts.add(join(segs));
            }
        }

        private static boolean hasParagraphAncestor(Element p) {
            for (Node n = p.getParentNode(); n != null; n = n.getParentNode()) {
                if (n.getNodeType() == Node.ELEMENT_NODE && W_NS.equals(n.getNamespaceURI())
                        && "p".equals(n.getLocalName())) return true;
            }
            return false;
        }

        private static void collect(Node n, List<Seg> out) {
            for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) c;
                String ln = e.getLocalName();
                if (W_NS.equals(e.getNamespaceURI())) {
                    if ("p".equals(ln) || "pPr".equals(ln) || "rPr".equals(ln)) continue;
                    if ("t".equals(ln)) {
                        out.add(new Seg(e, true, e.getTextContent()));
                        continue;
                    }
                    if ("tab".equals(ln) || "br".equals(ln) || "cr".equals(ln)) {
                        Node par = e.getParentNode();
                        if (par != null && "r".equals(par.getLocalName())) {
                            out.add(new Seg(e, false, null));
                        }
                        continue;
                    }
                } else if ("Fallback".equals(ln)) {
                    continue;
                }
                collect(e, out);
            }
        }

        private static String join(List<Seg> segs) {
            StringBuilder sb = new StringBuilder();
            for (Seg s : segs) sb.append(s.display());
            return sb.toString();
        }

        /** The editable paragraphs (empty ones are left out). */
        public List<String> texts() {
            return Collections.unmodifiableList(texts);
        }

        /** Applies the new paragraph texts (same size and order as [texts]) and returns the new .docx bytes. */
        public byte[] save(List<String> newTexts) throws Exception {
            for (int i = 0; i < paras.size() && i < newTexts.size(); i++) {
                String n = newTexts.get(i);
                if (n == null || n.equals(texts.get(i))) continue;
                editParagraph(paras.get(i), texts.get(i), n);
                texts.set(i, join(paras.get(i)));
            }
            entries.put("word/document.xml", toBytes(doc));
            return writeZip(entries);
        }

        /** Changes only the part of the paragraph that differs, so the formatting of the rest stays. */
        private static void editParagraph(List<Seg> segs, String o, String n) {
            int max = Math.min(o.length(), n.length());
            int p = 0;
            while (p < max && o.charAt(p) == n.charAt(p)) p++;
            int s = 0;
            while (s < max - p && o.charAt(o.length() - 1 - s) == n.charAt(n.length() - 1 - s)) s++;
            int oEnd = o.length() - s;
            String ins = n.substring(p, n.length() - s).replace('\n', ' ').replace('\t', ' ');

            int[] start = new int[segs.size()];
            int pos = 0;
            for (int i = 0; i < segs.size(); i++) {
                start[i] = pos;
                pos += segs.get(i).len();
            }

            int target = -1;
            int localOff = 0;
            if (!ins.isEmpty()) {
                if (oEnd > p) {
                    for (int i = 0; i < segs.size(); i++) {
                        Seg g = segs.get(i);
                        if (g.isText && start[i] < oEnd && start[i] + g.len() > p) {
                            target = i;
                            localOff = Math.max(0, p - start[i]);
                            break;
                        }
                    }
                }
                if (target < 0) {
                    int first = -1;
                    for (int i = 0; i < segs.size(); i++) {
                        Seg g = segs.get(i);
                        if (!g.isText) continue;
                        int a = start[i], b = a + g.len();
                        if (a <= p && p <= b) {
                            if (a < p) { target = i; localOff = p - a; break; }
                            if (first < 0) first = i;
                        }
                    }
                    if (target < 0 && first >= 0) { target = first; localOff = 0; }
                }
                if (target < 0) {
                    for (int i = 0; i < segs.size(); i++) {
                        if (segs.get(i).isText && start[i] >= p) { target = i; localOff = 0; break; }
                    }
                }
                if (target < 0) {
                    for (int i = segs.size() - 1; i >= 0; i--) {
                        if (segs.get(i).isText) { target = i; localOff = segs.get(i).len(); break; }
                    }
                }
            }

            for (int i = 0; i < segs.size(); i++) {
                Seg g = segs.get(i);
                int a = start[i], b = a + g.len();
                int from = Math.max(a, p), to = Math.min(b, oEnd);
                boolean overlap = from < to;
                if (!g.isText) {
                    if (overlap && g.el.getParentNode() != null) g.el.getParentNode().removeChild(g.el);
                    continue;
                }
                if (!overlap && i != target) continue;
                int cs, ce;
                if (overlap) { cs = from - a; ce = to - a; } else { cs = localOff; ce = localOff; }
                String t = g.text;
                String nt = t.substring(0, cs) + (i == target ? ins : "") + t.substring(ce);
                g.text = nt;
                g.el.setTextContent(nt);
                setPreserve(g.el);
            }
        }
    }

    // ------------------------------------------------------------------ XLSX

    public static final class XlsxEditor {
        public static final int MAX_ROWS = 5000;
        public static final int MAX_COLS = 60;
        private static final Pattern NUMBER = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$");

        private static final class SheetData {
            Document doc;
            Element sheetData;
            final TreeMap<Integer, Element> rows = new TreeMap<Integer, Element>();
            final HashMap<Long, Element> cells = new HashMap<Long, Element>();
            int maxRow = -1;
            int maxCol = -1;
            boolean dirty = false;
        }

        private final LinkedHashMap<String, byte[]> entries;
        private final List<String> names = new ArrayList<String>();
        private final List<String> paths = new ArrayList<String>();
        private final List<String> shared = new ArrayList<String>();
        private final SheetData[] sheets;
        private boolean calcFlagged = false;

        public XlsxEditor(byte[] data) throws Exception {
            entries = readZip(data);
            byte[] wbBytes = entries.get("xl/workbook.xml");
            if (wbBytes == null) throw new IllegalArgumentException("Not a valid .xlsx (no xl/workbook.xml)");
            Document wb = parseXml(wbBytes);

            Map<String, String> rels = new HashMap<String, String>();
            byte[] relBytes = entries.get("xl/_rels/workbook.xml.rels");
            if (relBytes != null) {
                NodeList rl = parseXml(relBytes).getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < rl.getLength(); i++) {
                    Element r = (Element) rl.item(i);
                    rels.put(r.getAttribute("Id"), r.getAttribute("Target"));
                }
            }
            NodeList sl = wb.getElementsByTagNameNS("*", "sheet");
            for (int i = 0; i < sl.getLength(); i++) {
                Element s = (Element) sl.item(i);
                String rid = s.getAttributeNS(REL_NS, "id");
                String target = rels.get(rid);
                if (target == null) continue;
                String path = resolve(target);
                if (!entries.containsKey(path)) continue;
                names.add(s.getAttribute("name"));
                paths.add(path);
            }
            if (names.isEmpty()) throw new IllegalArgumentException("No worksheets found in this file");
            sheets = new SheetData[names.size()];

            byte[] ss = entries.get("xl/sharedStrings.xml");
            if (ss != null) {
                NodeList si = parseXml(ss).getElementsByTagNameNS("*", "si");
                for (int i = 0; i < si.getLength(); i++) shared.add(textOfSi((Element) si.item(i)));
            }
        }

        private static String resolve(String target) {
            String t = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            ArrayList<String> parts = new ArrayList<String>();
            for (String p : t.split("/")) {
                if (p.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
                else if (!p.equals(".") && !p.isEmpty()) parts.add(p);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) { if (i > 0) sb.append('/'); sb.append(parts.get(i)); }
            return sb.toString();
        }

        private static String textOfSi(Element si) {
            StringBuilder sb = new StringBuilder();
            for (Node c = si.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) c;
                String ln = e.getLocalName();
                if ("t".equals(ln)) sb.append(e.getTextContent());
                else if ("r".equals(ln)) {
                    for (Node d = e.getFirstChild(); d != null; d = d.getNextSibling()) {
                        if (d.getNodeType() == Node.ELEMENT_NODE && "t".equals(d.getLocalName())) sb.append(d.getTextContent());
                    }
                }
            }
            return sb.toString();
        }

        public int sheetCount() { return names.size(); }

        public String sheetName(int i) { return names.get(i); }

        public int rowCount(int s) { return Math.min(MAX_ROWS, load(s).maxRow + 2); }

        public int colCount(int s) { return Math.min(MAX_COLS, load(s).maxCol + 2); }

        public boolean truncated(int s) { return load(s).maxRow + 1 > MAX_ROWS || load(s).maxCol + 1 > MAX_COLS; }

        private static long key(int r, int c) { return ((long) r << 20) | c; }

        public static String colName(int c) {
            StringBuilder sb = new StringBuilder();
            c++;
            while (c > 0) {
                int m = (c - 1) % 26;
                sb.insert(0, (char) ('A' + m));
                c = (c - 1) / 26;
            }
            return sb.toString();
        }

        private static int colIndex(String ref) {
            int c = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
                else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
                else break;
            }
            return c - 1;
        }

        private SheetData load(int s) {
            if (sheets[s] != null) return sheets[s];
            try {
                SheetData sd = new SheetData();
                sd.doc = parseXml(entries.get(paths.get(s)));
                NodeList sdl = sd.doc.getElementsByTagNameNS("*", "sheetData");
                if (sdl.getLength() == 0) throw new IllegalStateException("No sheetData");
                sd.sheetData = (Element) sdl.item(0);
                int prevRow = -1;
                for (Node n = sd.sheetData.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n.getNodeType() != Node.ELEMENT_NODE || !"row".equals(n.getLocalName())) continue;
                    Element row = (Element) n;
                    String ra = row.getAttribute("r");
                    int r = ra.isEmpty() ? prevRow + 1 : Integer.parseInt(ra) - 1;
                    prevRow = r;
                    sd.rows.put(r, row);
                    int prevCol = -1;
                    for (Node cn = row.getFirstChild(); cn != null; cn = cn.getNextSibling()) {
                        if (cn.getNodeType() != Node.ELEMENT_NODE || !"c".equals(cn.getLocalName())) continue;
                        Element cell = (Element) cn;
                        String ref = cell.getAttribute("r");
                        int c = ref.isEmpty() ? prevCol + 1 : colIndex(ref);
                        prevCol = c;
                        sd.cells.put(key(r, c), cell);
                        if (!valueOf(cell).isEmpty() || child(cell, "f") != null) {
                            if (r > sd.maxRow) sd.maxRow = r;
                            if (c > sd.maxCol) sd.maxCol = c;
                        }
                    }
                }
                sheets[s] = sd;
                return sd;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Couldn't read sheet: " + e.getMessage(), e);
            }
        }

        private String valueOf(Element c) {
            String t = c.getAttribute("t");
            if ("inlineStr".equals(t)) {
                Element is = child(c, "is");
                return is == null ? "" : textOfSi(is);
            }
            Element v = child(c, "v");
            String s = v == null ? "" : v.getTextContent();
            if ("s".equals(t)) {
                try {
                    int i = Integer.parseInt(s.trim());
                    return i >= 0 && i < shared.size() ? shared.get(i) : "";
                } catch (NumberFormatException e) {
                    return "";
                }
            }
            if ("b".equals(t)) return "1".equals(s.trim()) ? "TRUE" : "FALSE";
            return s;
        }

        public String get(int s, int r, int c) {
            Element cell = load(s).cells.get(key(r, c));
            return cell == null ? "" : valueOf(cell);
        }

        public boolean isFormula(int s, int r, int c) {
            Element cell = load(s).cells.get(key(r, c));
            return cell != null && child(cell, "f") != null;
        }

        public String formula(int s, int r, int c) {
            Element cell = load(s).cells.get(key(r, c));
            Element f = cell == null ? null : child(cell, "f");
            return f == null ? "" : f.getTextContent();
        }

        /** Sets a cell. Numbers are stored as numbers, everything else as text. Formula cells are refused. */
        public boolean set(int s, int r, int c, String text) {
            SheetData sd = load(s);
            Element cell = sd.cells.get(key(r, c));
            if (cell != null && child(cell, "f") != null) return false;
            if (text == null) text = "";
            if (cell == null) {
                if (text.isEmpty()) return true;
                cell = createCell(sd, r, c);
            }
            for (Element e = child(cell, "v"); e != null; e = child(cell, "v")) cell.removeChild(e);
            for (Element e = child(cell, "is"); e != null; e = child(cell, "is")) cell.removeChild(e);
            cell.removeAttribute("t");
            if (!text.isEmpty()) {
                if (NUMBER.matcher(text).matches() && text.length() <= 15) {
                    Element v = mk(cell, "v");
                    v.setTextContent(text);
                    cell.appendChild(v);
                } else {
                    cell.setAttribute("t", "inlineStr");
                    Element is = mk(cell, "is");
                    Element t = mk(is, "t");
                    setPreserve(t);
                    t.setTextContent(text);
                    is.appendChild(t);
                    cell.appendChild(is);
                }
                if (r > sd.maxRow) sd.maxRow = r;
                if (c > sd.maxCol) sd.maxCol = c;
            }
            sd.dirty = true;
            return true;
        }

        private Element createCell(SheetData sd, int r, int c) {
            Element row = sd.rows.get(r);
            if (row == null) {
                row = mk(sd.sheetData, "row");
                row.setAttribute("r", String.valueOf(r + 1));
                Map.Entry<Integer, Element> next = sd.rows.higherEntry(r);
                sd.sheetData.insertBefore(row, next == null ? null : next.getValue());
                sd.rows.put(r, row);
            }
            row.removeAttribute("spans");
            Element cell = mk(row, "c");
            cell.setAttribute("r", colName(c) + (r + 1));
            Node before = null;
            int prevCol = -1;
            for (Node n = row.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeType() != Node.ELEMENT_NODE || !"c".equals(n.getLocalName())) continue;
                String ref = ((Element) n).getAttribute("r");
                int cc = ref.isEmpty() ? prevCol + 1 : colIndex(ref);
                prevCol = cc;
                if (cc > c) { before = n; break; }
            }
            row.insertBefore(cell, before);
            sd.cells.put(key(r, c), cell);
            return cell;
        }

        /** Returns the new .xlsx bytes. */
        public byte[] save() throws Exception {
            boolean any = false;
            for (int i = 0; i < sheets.length; i++) {
                SheetData sd = sheets[i];
                if (sd != null && sd.dirty) {
                    entries.put(paths.get(i), toBytes(sd.doc));
                    sd.dirty = false;
                    any = true;
                }
            }
            if (any && !calcFlagged) {
                flagRecalc();
                calcFlagged = true;
            }
            return writeZip(entries);
        }

        /** Asks Excel to recalculate formulas when the file is opened, since edited cells may feed them. */
        private void flagRecalc() throws Exception {
            Document wb = parseXml(entries.get("xl/workbook.xml"));
            Element root = wb.getDocumentElement();
            Element calc = child(root, "calcPr");
            if (calc == null) {
                calc = mk(root, "calcPr");
                String after = ",oleSize,customWorkbookViews,pivotCaches,smartTagPr,smartTagTypes,webPublishing,fileRecoveryPr,webPublishObjects,extLst,";
                Node before = null;
                for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n.getNodeType() == Node.ELEMENT_NODE && after.contains("," + n.getLocalName() + ",")) {
                        before = n;
                        break;
                    }
                }
                root.insertBefore(calc, before);
            }
            calc.setAttribute("fullCalcOnLoad", "1");
            entries.put("xl/workbook.xml", toBytes(wb));
        }
    }
}
