package com.example.pdfunlock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Reads the text of .pptx presentations, slide by slide. */
final class PptxReader {
    private PptxReader() {}

    private static final Pattern SLIDE = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");

    static List<Office.Slide> read(byte[] data) throws Exception {
        Map<String, byte[]> z = Office.readZip(data, "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/slides/");
        if (z.get("ppt/presentation.xml") == null && z.isEmpty()) {
            throw new IOException("This PowerPoint file is protected or is not a valid .pptx");
        }

        ArrayList<String> paths = new ArrayList<String>();
        byte[] pres = z.get("ppt/presentation.xml");
        byte[] relXml = z.get("ppt/_rels/presentation.xml.rels");
        if (pres != null && relXml != null) {
            final Map<String, String> rels = new HashMap<String, String>();
            Office.sax(relXml, new DefaultHandler() {
                @Override
                public void startElement(String uri, String ln, String q, Attributes a) {
                    if (q.equals("Relationship")) rels.put(a.getValue("Id"), a.getValue("Target"));
                }
            });
            final ArrayList<String> ids = new ArrayList<String>();
            Office.sax(pres, new DefaultHandler() {
                @Override
                public void startElement(String uri, String ln, String q, Attributes a) {
                    if (q.equals("p:sldId") || q.endsWith(":sldId") || q.equals("sldId")) ids.add(Office.relId(a));
                }
            });
            for (String id : ids) {
                String t = rels.get(id);
                if (t == null) continue;
                String p = t.startsWith("/") ? t.substring(1) : "ppt/" + t;
                if (z.containsKey(p)) paths.add(p);
            }
        }
        if (paths.isEmpty()) {
            for (String k : z.keySet()) if (SLIDE.matcher(k).matches()) paths.add(k);
            Collections.sort(paths, new Comparator<String>() {
                @Override
                public int compare(String a, String b) { return Integer.compare(num(a), num(b)); }
            });
        }

        ArrayList<Office.Slide> out = new ArrayList<Office.Slide>();
        for (String p : paths) {
            SlideHandler h = new SlideHandler();
            Office.sax(z.get(p), h);
            out.add(h.slide);
        }
        return out;
    }

    private static int num(String name) {
        Matcher m = SLIDE.matcher(name);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static final class SlideHandler extends DefaultHandler {
        final Office.Slide slide = new Office.Slide();
        private StringBuilder para = new StringBuilder();
        private boolean inT = false, titleShape = false;

        @Override
        public void startElement(String uri, String ln, String q, Attributes a) {
            switch (q) {
                case "p:sp":
                    titleShape = false;
                    break;
                case "p:ph": {
                    String t = a.getValue("type");
                    if ("title".equals(t) || "ctrTitle".equals(t)) titleShape = true;
                    break;
                }
                case "a:p":
                    para = new StringBuilder();
                    break;
                case "a:t":
                    inT = true;
                    break;
                case "a:br":
                    para.append('\n');
                    break;
                case "a:tab":
                    para.append('\t');
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int s, int l) {
            if (inT) para.append(ch, s, l);
        }

        @Override
        public void endElement(String uri, String ln, String q) {
            if (q.equals("a:t")) {
                inT = false;
            } else if (q.equals("a:p")) {
                String s = para.toString().trim();
                if (s.isEmpty()) return;
                if (titleShape) {
                    slide.title = slide.title == null ? s : slide.title + " " + s;
                } else {
                    slide.body.add(s);
                }
            }
        }
    }
}
