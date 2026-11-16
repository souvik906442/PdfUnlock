package com.example.pdfunlock;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Reads the text of classic PowerPoint 97-2003 (.ppt) files. */
final class PptReader {
    private static final Charset UTF16 = Charset.forName("UTF-16LE");
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private final byte[] b;
    private final ArrayList<Office.Slide> listSlides = new ArrayList<Office.Slide>();
    private final ArrayList<Office.Slide> boxSlides = new ArrayList<Office.Slide>();
    private boolean inList = false;
    private Office.Slide curBox = null;
    private int textType = -1;

    private PptReader(byte[] b) { this.b = b; }

    static List<Office.Slide> read(byte[] data) throws IOException {
        Ole2 ole = new Ole2(data);
        byte[] s = ole.stream("PowerPoint Document");
        if (s == null) {
            if (ole.has("EncryptedPackage")) throw new IOException("This PowerPoint file is password-protected");
            throw new IOException("No PowerPoint data found in this file");
        }
        PptReader r = new PptReader(s);
        try {
            r.walk(0, s.length);
        } catch (RuntimeException e) {
            if (r.listSlides.isEmpty() && r.boxSlides.isEmpty()) {
                throw new IOException("This PowerPoint file looks damaged");
            }
        }
        return r.merge();
    }

    private void walk(int start, int end) {
        int pos = start;
        while (pos + 8 <= end) {
            int verInst = Office.u16(b, pos);
            int type = Office.u16(b, pos + 2);
            long len = Office.u32(b, pos + 4);
            int dp = pos + 8;
            if (dp + len > end) break;
            int ver = verInst & 0xF, inst = verInst >> 4;
            int e = (int) (dp + len);
            if (ver == 0xF) {
                if (type == 1006) { // Slide
                    Office.Slide s = new Office.Slide();
                    boxSlides.add(s);
                    curBox = s;
                    textType = -1;
                    walk(dp, e);
                    curBox = null;
                } else if (type == 4080 && inst == 0) { // SlideListWithText (slides)
                    inList = true;
                    textType = -1;
                    walk(dp, e);
                    inList = false;
                } else {
                    walk(dp, e);
                }
            } else if (type == 1011) { // SlidePersistAtom: a new slide starts in the list
                if (inList) listSlides.add(new Office.Slide());
            } else if (type == 3999 && len >= 4) { // TextHeaderAtom
                textType = (int) Office.u32(b, dp);
            } else if (type == 4000) { // TextCharsAtom
                addText(new String(b, dp, (int) (len & ~1L), UTF16));
            } else if (type == 4008) { // TextBytesAtom
                addText(new String(b, dp, (int) len, CP1252));
            }
            pos = e;
        }
    }

    private void addText(String raw) {
        Office.Slide target = null;
        if (curBox != null) target = curBox;
        else if (inList && !listSlides.isEmpty()) target = listSlides.get(listSlides.size() - 1);
        boolean title = textType == 0 || textType == 6;
        boolean other = textType == 4;
        textType = -1;
        if (target == null) return;
        // slide-number / date placeholders only hold a stand-in like "0" or "*"
        if (other && raw.trim().matches("[0-9*#\\s]{0,4}")) return;
        String t = raw.replace('\u000B', ' ').replace("\r\n", "\r").replace('\n', '\r');
        for (String line : t.split("\r")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (title) target.title = target.title == null ? s : target.title + " " + s;
            else target.body.add(s);
        }
    }

    private List<Office.Slide> merge() {
        int n = Math.max(listSlides.size(), boxSlides.size());
        ArrayList<Office.Slide> out = new ArrayList<Office.Slide>();
        for (int i = 0; i < n; i++) {
            Office.Slide l = i < listSlides.size() ? listSlides.get(i) : null;
            Office.Slide x = i < boxSlides.size() ? boxSlides.get(i) : null;
            Office.Slide m = new Office.Slide();
            HashSet<String> seen = new HashSet<String>();
            if (l != null) {
                m.title = l.title;
                if (l.title != null) seen.add(l.title);
                for (String s : l.body) { m.body.add(s); seen.add(s); }
            }
            if (x != null) {
                if (m.title == null && x.title != null && seen.add(x.title)) m.title = x.title;
                else if (x.title != null && seen.add(x.title)) m.body.add(x.title);
                for (String s : x.body) if (seen.add(s)) m.body.add(s);
            }
            out.add(m);
        }
        return out;
    }
}
