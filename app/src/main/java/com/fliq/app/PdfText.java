package com.fliq.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Small, dependency-free PDF text extractor (no Android classes, so it can be tested on a plain JVM).
 * It finds the objects by scanning the file, reads pages, fonts (ToUnicode / encodings / widths) and the
 * text operators, and rebuilds lines and paragraphs. It does not do OCR, images, tables or encryption.
 */
public final class PdfText {

    public interface Out {
        void paragraph(String text, float sizePt, boolean bold, boolean newPage) throws IOException;
    }

    /** Reads [data] and reports every paragraph in reading order. Returns the number of glyph codes it could not map. */
    public static int extract(byte[] data, Out out) throws IOException {
        Doc d = new Doc(data);
        d.scan();
        d.expandObjectStreams();
        if (d.trailer.containsKey("Encrypt")) throw new IOException("This PDF is password-protected");
        List<Page> pages = d.pages();
        boolean[] emitted = {false};
        for (Page pg : pages) {
            List<Item> items = new ArrayList<>();
            try {
                d.pageItems(pg, items);
            } catch (RuntimeException e) {
                // a broken page should not stop the rest
            } catch (StackOverflowError e) {
            }
            emitPage(items, out, emitted);
        }
        return d.unmapped;
    }

    // ------------------------------------------------------------------ object model

    static final class Name {
        final String v;
        Name(String v) { this.v = v; }
        @Override public String toString() { return "/" + v; }
    }

    static final class Ref {
        final int num;
        Ref(int n) { num = n; }
    }

    static final class Op {
        final String v;
        Op(String v) { this.v = v; }
    }

    static final class Str {
        final byte[] b;
        Str(byte[] b) { this.b = b; }
    }

    static final class Stm {
        final Map<String, Object> dict;
        final int start, len;
        Stm(Map<String, Object> d, int s, int l) { dict = d; start = s; len = l; }
    }

    static final Object EOF = new Object();
    static final Object NULL = new Object();

    static final class Page {
        final Map<String, Object> dict;
        final Object resources;
        Page(Map<String, Object> d, Object r) { dict = d; resources = r; }
    }

    static final class Item {
        double x0, x1, y, fs;
        String text;
        boolean bold;
    }

    // ------------------------------------------------------------------ lexer

    static final class Lexer {
        final byte[] b;
        int p;
        final int end;

        Lexer(byte[] b, int p, int end) {
            this.b = b;
            this.p = p;
            this.end = end;
        }

        static boolean isWs(int c) { return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32; }

        static boolean isDelim(int c) {
            return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
        }

        void skipWs() {
            while (p < end) {
                int c = b[p] & 255;
                if (isWs(c)) p++;
                else if (c == '%') {
                    while (p < end && b[p] != 10 && b[p] != 13) p++;
                } else break;
            }
        }

        /** Next token; with [refs] "n g R" is returned as a Ref (only objects, never content streams). */
        Object value(boolean refs) {
            Object o = next(refs);
            if (refs && o instanceof Double) {
                double d = (Double) o;
                if (d >= 0 && d == Math.floor(d)) {
                    int save = p;
                    Object o2 = next(false);
                    if (o2 instanceof Double && (Double) o2 >= 0) {
                        Object o3 = next(false);
                        if (o3 instanceof Op && ((Op) o3).v.equals("R")) return new Ref((int) d);
                    }
                    p = save;
                }
            }
            return o;
        }

        Object next(boolean refs) {
            skipWs();
            if (p >= end) return EOF;
            int c = b[p] & 255;
            switch (c) {
                case '/':
                    p++;
                    return new Name(readName());
                case '(':
                    p++;
                    return new Str(readLiteral());
                case '<':
                    if (p + 1 < end && b[p + 1] == '<') {
                        p += 2;
                        return readDict(refs);
                    }
                    p++;
                    return new Str(readHex());
                case '[':
                    p++;
                    return readArray(refs);
                case '>':
                    if (p + 1 < end && b[p + 1] == '>') {
                        p += 2;
                        return new Op(">>");
                    }
                    p++;
                    return new Op(">");
                case ']': case ')': case '{': case '}':
                    p++;
                    return new Op(String.valueOf((char) c));
                default:
                    break;
            }
            int s = p;
            while (p < end) {
                int ch = b[p] & 255;
                if (isWs(ch) || isDelim(ch)) break;
                p++;
            }
            if (p == s) {
                p++;
                return new Op("?");
            }
            String tok = new String(b, s, p - s, Charset.forName("ISO-8859-1"));
            char f = tok.charAt(0);
            if ((f >= '0' && f <= '9') || f == '+' || f == '-' || f == '.') {
                try {
                    return Double.valueOf(tok);
                } catch (NumberFormatException e) {
                    int dash = tok.indexOf('-', 1);
                    if (dash > 0) {
                        try {
                            return Double.valueOf(tok.substring(0, dash));
                        } catch (NumberFormatException e2) {
                        }
                    }
                    return new Op(tok);
                }
            }
            if (tok.equals("true")) return Boolean.TRUE;
            if (tok.equals("false")) return Boolean.FALSE;
            if (tok.equals("null")) return NULL;
            return new Op(tok);
        }

        String readName() {
            StringBuilder sb = new StringBuilder();
            while (p < end) {
                int c = b[p] & 255;
                if (isWs(c) || isDelim(c)) break;
                if (c == '#' && p + 2 < end && hex(b[p + 1]) >= 0 && hex(b[p + 2]) >= 0) {
                    sb.append((char) (hex(b[p + 1]) * 16 + hex(b[p + 2])));
                    p += 3;
                } else {
                    sb.append((char) c);
                    p++;
                }
            }
            return sb.toString();
        }

        static int hex(int c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        }

        byte[] readLiteral() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int depth = 1;
            while (p < end) {
                int c = b[p++] & 255;
                if (c == '\\') {
                    if (p >= end) break;
                    int e = b[p++] & 255;
                    switch (e) {
                        case 'n': o.write(10); break;
                        case 'r': o.write(13); break;
                        case 't': o.write(9); break;
                        case 'b': o.write(8); break;
                        case 'f': o.write(12); break;
                        case '\r':
                            if (p < end && b[p] == '\n') p++;
                            break;
                        case '\n':
                            break;
                        default:
                            if (e >= '0' && e <= '7') {
                                int v = e - '0';
                                for (int k = 0; k < 2 && p < end && b[p] >= '0' && b[p] <= '7'; k++) v = v * 8 + (b[p++] - '0');
                                o.write(v & 255);
                            } else {
                                o.write(e);
                            }
                    }
                } else if (c == '(') {
                    depth++;
                    o.write(c);
                } else if (c == ')') {
                    if (--depth == 0) break;
                    o.write(c);
                } else {
                    o.write(c);
                }
            }
            return o.toByteArray();
        }

        byte[] readHex() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int hi = -1;
            while (p < end) {
                int c = b[p++] & 255;
                if (c == '>') break;
                int h = hex(c);
                if (h < 0) continue;
                if (hi < 0) hi = h;
                else {
                    o.write(hi * 16 + h);
                    hi = -1;
                }
            }
            if (hi >= 0) o.write(hi * 16);
            return o.toByteArray();
        }

        Map<String, Object> readDict(boolean refs) {
            Map<String, Object> m = new HashMap<>();
            while (true) {
                Object k = next(false);
                if (k == EOF) break;
                if (k instanceof Op && ((Op) k).v.equals(">>")) break;
                if (!(k instanceof Name)) continue;
                Object v = value(refs);
                if (v == EOF) break;
                if (v instanceof Op && ((Op) v).v.equals(">>")) break;
                m.put(((Name) k).v, v);
            }
            return m;
        }

        List<Object> readArray(boolean refs) {
            List<Object> l = new ArrayList<>();
            while (true) {
                Object v = value(refs);
                if (v == EOF) break;
                if (v instanceof Op && ((Op) v).v.equals("]")) break;
                l.add(v);
            }
            return l;
        }
    }

    // ------------------------------------------------------------------ document

    static final class Doc {
        final byte[] buf;
        final Map<Integer, Object> objs = new HashMap<>();
        final Map<String, Object> trailer = new HashMap<>();
        final Map<Object, Font> fonts = new IdentityHashMap<>();
        int unmapped = 0;

        Doc(byte[] b) { buf = b; }

        void scan() throws IOException {
            if (buf.length < 8) throw new IOException("Can't read this PDF");
            Lexer lx = new Lexer(buf, 0, buf.length);
            Object p1 = null, p2 = null;
            while (true) {
                Object t = lx.next(false);
                if (t == EOF) break;
                if (t instanceof Op) {
                    String v = ((Op) t).v;
                    if (v.equals("obj") && p1 instanceof Double && p2 instanceof Double) {
                        int num = (int) (double) (Double) p2;
                        Object val = lx.value(true);
                        int save = lx.p;
                        Object nx = lx.next(false);
                        if (nx instanceof Op && ((Op) nx).v.equals("stream") && val instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> dm = (Map<String, Object>) val;
                            val = readStream(lx, dm);
                            Object ty = dm.get("Type");
                            if (ty instanceof Name && ((Name) ty).v.equals("XRef")) {
                                for (String k : new String[]{"Root", "Encrypt"}) if (dm.containsKey(k)) trailer.put(k, dm.get(k));
                            }
                        } else {
                            lx.p = save;
                        }
                        objs.put(num, val);
                        p1 = null;
                        p2 = null;
                        continue;
                    } else if (v.equals("trailer")) {
                        Object dd = lx.value(true);
                        if (dd instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> dm = (Map<String, Object>) dd;
                            for (String k : new String[]{"Root", "Encrypt"}) if (dm.containsKey(k)) trailer.put(k, dm.get(k));
                        }
                        p1 = null;
                        p2 = null;
                        continue;
                    }
                }
                p2 = p1;
                p1 = t;
            }
        }

        private Stm readStream(Lexer lx, Map<String, Object> dict) {
            int p = lx.p;
            if (p < buf.length && buf[p] == '\r') {
                p++;
                if (p < buf.length && buf[p] == '\n') p++;
            } else if (p < buf.length && buf[p] == '\n') {
                p++;
            }
            int start = p;
            int len = -1;
            Object l = dict.get("Length");
            if (l instanceof Ref) l = objs.get(((Ref) l).num);
            if (l instanceof Double) len = (int) (double) (Double) l;
            if (len >= 0 && start + len <= buf.length) {
                int q = start + len;
                while (q < buf.length && Lexer.isWs(buf[q] & 255)) q++;
                if (startsWith(q, "endstream")) {
                    lx.p = q + 9;
                    return new Stm(dict, start, len);
                }
            }
            int e = indexOf("endstream", start);
            if (e < 0) {
                lx.p = buf.length;
                return new Stm(dict, start, buf.length - start);
            }
            int de = e;
            if (de > start && buf[de - 1] == '\n') de--;
            if (de > start && buf[de - 1] == '\r') de--;
            lx.p = e + 9;
            return new Stm(dict, start, de - start);
        }

        private boolean startsWith(int at, String s) {
            if (at + s.length() > buf.length) return false;
            for (int i = 0; i < s.length(); i++) if (buf[at + i] != s.charAt(i)) return false;
            return true;
        }

        private int indexOf(String s, int from) {
            byte f = (byte) s.charAt(0);
            for (int i = from; i + s.length() <= buf.length; i++) {
                if (buf[i] == f && startsWith(i, s)) return i;
            }
            return -1;
        }

        void expandObjectStreams() {
            List<Stm> list = new ArrayList<>();
            for (Object o : objs.values()) {
                if (o instanceof Stm && isName(((Stm) o).dict.get("Type"), "ObjStm")) list.add((Stm) o);
            }
            for (Stm s : list) {
                try {
                    byte[] data = decode(s);
                    int n = intOf(resolve(s.dict.get("N")), 0);
                    int first = intOf(resolve(s.dict.get("First")), 0);
                    Lexer h = new Lexer(data, 0, data.length);
                    int[] nums = new int[n];
                    int[] offs = new int[n];
                    int cnt = 0;
                    for (int i = 0; i < n; i++) {
                        Object a = h.next(false), b = h.next(false);
                        if (!(a instanceof Double) || !(b instanceof Double)) break;
                        nums[i] = (int) (double) (Double) a;
                        offs[i] = (int) (double) (Double) b;
                        cnt++;
                    }
                    for (int i = 0; i < cnt; i++) {
                        int at = first + offs[i];
                        if (at < 0 || at >= data.length) continue;
                        Object val = new Lexer(data, at, data.length).value(true);
                        if (!objs.containsKey(nums[i])) objs.put(nums[i], val);
                    }
                } catch (IOException | RuntimeException e) {
                    // skip a broken object stream
                }
            }
        }

        // ---- helpers

        Object resolve(Object o) {
            int guard = 0;
            while (o instanceof Ref && guard++ < 20) o = objs.get(((Ref) o).num);
            return o == NULL ? null : o;
        }

        static boolean isName(Object o, String v) { return o instanceof Name && ((Name) o).v.equals(v); }

        static int intOf(Object o, int def) { return o instanceof Double ? (int) (double) (Double) o : def; }

        static double numOf(Object o, double def) { return o instanceof Double ? (Double) o : def; }

        @SuppressWarnings("unchecked")
        Map<String, Object> dictOf(Object o) {
            o = resolve(o);
            if (o instanceof Map) return (Map<String, Object>) o;
            if (o instanceof Stm) return ((Stm) o).dict;
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Object> listOf(Object o) {
            o = resolve(o);
            if (o instanceof List) return (List<Object>) o;
            return null;
        }

        // ---- pages

        List<Page> pages() throws IOException {
            List<Page> out = new ArrayList<>();
            Map<String, Object> root = dictOf(trailer.get("Root"));
            if (root == null) {
                for (Object o : objs.values()) {
                    if (o instanceof Map && isName(((Map<?, ?>) o).get("Type"), "Catalog")) {
                        root = dictOf(o);
                        break;
                    }
                }
            }
            if (root != null) {
                Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                walk(root.get("Pages"), null, 0, seen, out);
            }
            if (out.isEmpty()) {
                List<Integer> keys = new ArrayList<>(objs.keySet());
                Collections.sort(keys);
                for (int k : keys) {
                    Object o = objs.get(k);
                    if (o instanceof Map && isName(((Map<?, ?>) o).get("Type"), "Page")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) o;
                        out.add(new Page(m, m.get("Resources")));
                    }
                }
            }
            if (out.isEmpty()) throw new IOException("Can't read this PDF");
            return out;
        }

        private void walk(Object node, Object inheritedRes, int depth, Set<Object> seen, List<Page> out) {
            if (depth > 40) return;
            Map<String, Object> m = dictOf(node);
            if (m == null || !seen.add(m)) return;
            Object res = m.containsKey("Resources") ? m.get("Resources") : inheritedRes;
            List<Object> kids = listOf(m.get("Kids"));
            if (kids != null) {
                for (Object k : kids) walk(k, res, depth + 1, seen, out);
            } else {
                out.add(new Page(m, res));
            }
        }

        void pageItems(Page pg, List<Item> items) throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            Object c = resolve(pg.dict.get("Contents"));
            if (c instanceof Stm) {
                byte[] d = decode((Stm) c);
                all.write(d, 0, d.length);
            } else if (c instanceof List) {
                for (Object o : (List<?>) c) {
                    Object r = resolve(o);
                    if (r instanceof Stm) {
                        byte[] d = decode((Stm) r);
                        all.write(d, 0, d.length);
                        all.write('\n');
                    }
                }
            }
            byte[] content = all.toByteArray();
            Interp in = new Interp(this, items);
            in.run(content, dictOf(pg.resources), new double[]{1, 0, 0, 1, 0, 0}, 0);
        }

        // ---- stream decoding

        byte[] decode(Stm s) throws IOException {
            byte[] data = Arrays.copyOfRange(buf, s.start, s.start + Math.max(0, s.len));
            Object f = resolve(s.dict.get("Filter"));
            if (f == null) f = resolve(s.dict.get("F"));
            Object dp = resolve(s.dict.get("DecodeParms"));
            if (dp == null) dp = resolve(s.dict.get("DP"));
            List<Object> filters = new ArrayList<>();
            if (f instanceof List) filters.addAll((List<?>) f);
            else if (f != null) filters.add(f);
            List<Object> parms = new ArrayList<>();
            if (dp instanceof List) parms.addAll((List<?>) dp);
            else if (dp != null) parms.add(dp);
            for (int i = 0; i < filters.size(); i++) {
                Object fo = resolve(filters.get(i));
                if (!(fo instanceof Name)) continue;
                String n = ((Name) fo).v;
                Map<String, Object> parm = i < parms.size() ? dictOf(parms.get(i)) : null;
                switch (n) {
                    case "FlateDecode": case "Fl":
                        data = predictor(inflate(data), parm);
                        break;
                    case "ASCIIHexDecode": case "AHx":
                        data = new Lexer(data, 0, data.length).readHex();
                        break;
                    case "ASCII85Decode": case "A85":
                        data = ascii85(data);
                        break;
                    case "Crypt":
                        break;
                    default:
                        return new byte[0];   // image filters etc.: not text
                }
            }
            return data;
        }

        private static byte[] inflate(byte[] in) {
            Inflater inf = new Inflater();
            inf.setInput(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, in.length * 3));
            byte[] tmp = new byte[8192];
            try {
                while (!inf.finished()) {
                    int n = inf.inflate(tmp);
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    bo.write(tmp, 0, n);
                    if (bo.size() > 96 * 1024 * 1024) break;
                }
            } catch (DataFormatException e) {
                // keep what was decoded
            } finally {
                inf.end();
            }
            return bo.toByteArray();
        }

        private byte[] predictor(byte[] d, Map<String, Object> parm) {
            if (parm == null) return d;
            int pred = intOf(resolve(parm.get("Predictor")), 1);
            if (pred < 10) return d;
            int cols = intOf(resolve(parm.get("Columns")), 1);
            int colors = intOf(resolve(parm.get("Colors")), 1);
            int bpc = intOf(resolve(parm.get("BitsPerComponent")), 8);
            int rowBytes = (cols * colors * bpc + 7) / 8;
            int bpp = Math.max(1, (colors * bpc + 7) / 8);
            int rows = d.length / (rowBytes + 1);
            byte[] out = new byte[rows * rowBytes];
            for (int r = 0; r < rows; r++) {
                int ft = d[r * (rowBytes + 1)] & 255;
                int src = r * (rowBytes + 1) + 1;
                int dst = r * rowBytes;
                for (int i = 0; i < rowBytes; i++) {
                    int x = d[src + i] & 255;
                    int a = i >= bpp ? out[dst + i - bpp] & 255 : 0;
                    int b = r > 0 ? out[dst - rowBytes + i] & 255 : 0;
                    int c = (i >= bpp && r > 0) ? out[dst - rowBytes + i - bpp] & 255 : 0;
                    int v;
                    switch (ft) {
                        case 1: v = x + a; break;
                        case 2: v = x + b; break;
                        case 3: v = x + ((a + b) >> 1); break;
                        case 4: {
                            int pp = a + b - c;
                            int pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                            v = x + ((pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c));
                            break;
                        }
                        default: v = x;
                    }
                    out[dst + i] = (byte) v;
                }
            }
            return out;
        }

        private static byte[] ascii85(byte[] in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            long acc = 0;
            int n = 0;
            for (int i = 0; i < in.length; i++) {
                int c = in[i] & 255;
                if (c == '~') break;
                if (Lexer.isWs(c)) continue;
                if (c == 'z' && n == 0) {
                    o.write(0); o.write(0); o.write(0); o.write(0);
                    continue;
                }
                if (c < '!' || c > 'u') continue;
                acc = acc * 85 + (c - '!');
                if (++n == 5) {
                    for (int k = 3; k >= 0; k--) o.write((int) (acc >> (8 * k)) & 255);
                    acc = 0;
                    n = 0;
                }
            }
            if (n > 1) {
                for (int k = n; k < 5; k++) acc = acc * 85 + 84;
                for (int k = 3; k > 4 - n; k--) o.write((int) (acc >> (8 * k)) & 255);
            }
            return o.toByteArray();
        }

        // ---- fonts

        Font font(Object fontObj) {
            Object r = resolve(fontObj);
            if (!(r instanceof Map)) return null;
            Font f = fonts.get(r);
            if (f == null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) r;
                try {
                    f = new Font(this, m);
                } catch (RuntimeException | IOException e) {
                    f = new Font();
                }
                fonts.put(r, f);
            }
            return f;
        }
    }

    // ------------------------------------------------------------------ fonts

    static final class Font {
        boolean composite;
        int codeLen = 1;
        Map<Integer, String> uni;
        final String[] enc = new String[256];
        double[] widths;
        int first;
        double missing = 500;
        Map<Integer, Double> cw;
        double dw = 1000;
        boolean bold;

        Font() { }

        Font(Doc d, Map<String, Object> fd) throws IOException {
            Object sub = d.resolve(fd.get("Subtype"));
            String base = d.resolve(fd.get("BaseFont")) instanceof Name ? ((Name) d.resolve(fd.get("BaseFont"))).v : "";
            String lb = base.toLowerCase();
            bold = lb.contains("bold") || lb.contains("black") || lb.contains("heavy");
            composite = Doc.isName(sub, "Type0");
            if (composite) {
                codeLen = 2;
                List<Object> desc = d.listOf(fd.get("DescendantFonts"));
                Map<String, Object> df = desc != null && !desc.isEmpty() ? d.dictOf(desc.get(0)) : null;
                if (df != null) {
                    dw = Doc.numOf(d.resolve(df.get("DW")), 1000);
                    cw = new HashMap<>();
                    List<Object> w = d.listOf(df.get("W"));
                    if (w != null) parseW(d, w);
                    Map<String, Object> fdesc = d.dictOf(df.get("FontDescriptor"));
                    if (fdesc != null) bold |= (Doc.intOf(d.resolve(fdesc.get("Flags")), 0) & 262144) != 0;
                }
            } else {
                Map<String, Object> fdesc = d.dictOf(fd.get("FontDescriptor"));
                if (fdesc != null) {
                    missing = Doc.numOf(d.resolve(fdesc.get("MissingWidth")), 500);
                    bold |= (Doc.intOf(d.resolve(fdesc.get("Flags")), 0) & 262144) != 0;
                } else if (lb.contains("courier")) {
                    missing = 600;
                }
                first = Doc.intOf(d.resolve(fd.get("FirstChar")), 0);
                List<Object> w = d.listOf(fd.get("Widths"));
                if (w != null) {
                    widths = new double[w.size()];
                    for (int i = 0; i < widths.length; i++) widths[i] = Doc.numOf(d.resolve(w.get(i)), missing);
                }
                buildEncoding(d, fd.get("Encoding"));
            }
            Object tu = d.resolve(fd.get("ToUnicode"));
            if (tu instanceof Stm) parseCMap(d.decode((Stm) tu));
        }

        private void parseW(Doc d, List<Object> w) {
            int i = 0;
            while (i < w.size()) {
                Object a = d.resolve(w.get(i));
                if (!(a instanceof Double)) { i++; continue; }
                int c1 = (int) (double) (Double) a;
                if (i + 1 >= w.size()) break;
                Object b = d.resolve(w.get(i + 1));
                if (b instanceof List) {
                    List<?> l = (List<?>) b;
                    for (int k = 0; k < l.size() && k < 65536; k++) cw.put(c1 + k, Doc.numOf(d.resolve(l.get(k)), dw));
                    i += 2;
                } else if (b instanceof Double && i + 2 < w.size()) {
                    int c2 = (int) (double) (Double) b;
                    double wd = Doc.numOf(d.resolve(w.get(i + 2)), dw);
                    for (int c = c1; c <= c2 && c - c1 < 65536; c++) cw.put(c, wd);
                    i += 3;
                } else {
                    i++;
                }
            }
        }

        private void buildEncoding(Doc d, Object encObj) {
            Object e = d.resolve(encObj);
            String baseName = "WinAnsiEncoding";
            List<Object> diffs = null;
            if (e instanceof Name) baseName = ((Name) e).v;
            else if (e instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> em = (Map<String, Object>) e;
                Object bn = d.resolve(em.get("BaseEncoding"));
                if (bn instanceof Name) baseName = ((Name) bn).v;
                diffs = d.listOf(em.get("Differences"));
            }
            Charset cs;
            try {
                cs = Charset.forName(baseName.startsWith("MacRoman") ? "x-MacRoman" : "windows-1252");
            } catch (RuntimeException ex) {
                cs = Charset.forName("ISO-8859-1");
            }
            for (int c = 32; c < 256; c++) {
                String s = new String(new byte[]{(byte) c}, cs);
                enc[c] = s.equals("�") ? null : s;
            }
            enc[127] = null;
            if (diffs != null) {
                int code = 0;
                for (Object o : diffs) {
                    Object r = d.resolve(o);
                    if (r instanceof Double) code = (int) (double) (Double) r;
                    else if (r instanceof Name) {
                        if (code >= 0 && code < 256) {
                            String g = glyph(((Name) r).v);
                            if (g != null) enc[code] = g;
                        }
                        code++;
                    }
                }
            }
        }

        private void parseCMap(byte[] data) {
            uni = new HashMap<>();
            Lexer lx = new Lexer(data, 0, data.length);
            List<Object> st = new ArrayList<>();
            while (true) {
                Object t = lx.next(false);
                if (t == EOF) break;
                if (!(t instanceof Op)) {
                    st.add(t);
                    if (st.size() > 4096) st.clear();
                    continue;
                }
                String op = ((Op) t).v;
                if (op.equals("endbfchar")) {
                    for (int i = 0; i + 1 < st.size(); i += 2) {
                        if (st.get(i) instanceof Str) {
                            String s = dst(st.get(i + 1));
                            if (s != null) uni.put(code(((Str) st.get(i)).b), s);
                        }
                    }
                    st.clear();
                } else if (op.equals("endbfrange")) {
                    for (int i = 0; i + 2 < st.size(); i += 3) {
                        if (!(st.get(i) instanceof Str) || !(st.get(i + 1) instanceof Str)) continue;
                        int lo = code(((Str) st.get(i)).b), hi = code(((Str) st.get(i + 1)).b);
                        Object dd = st.get(i + 2);
                        if (dd instanceof List) {
                            List<?> l = (List<?>) dd;
                            for (int k = 0; k < l.size() && lo + k <= hi; k++) {
                                String s = dst(l.get(k));
                                if (s != null) uni.put(lo + k, s);
                            }
                        } else {
                            String s = dst(dd);
                            if (s == null || s.isEmpty()) continue;
                            for (int c = lo; c <= hi && c - lo < 65536; c++) {
                                int last = s.codePointBefore(s.length());
                                String head = s.substring(0, s.length() - Character.charCount(last));
                                uni.put(c, head + new String(Character.toChars(last + (c - lo))));
                            }
                        }
                    }
                    st.clear();
                } else if (op.equals("endcodespacerange")) {
                    if (composite && !st.isEmpty() && st.get(0) instanceof Str) {
                        int n = ((Str) st.get(0)).b.length;
                        if (n == 1 || n == 2) codeLen = n;
                    }
                    st.clear();
                } else if (op.startsWith("begin") || op.startsWith("end") || op.equals("def") || op.equals("dict")) {
                    st.clear();
                }
            }
        }

        private static int code(byte[] b) {
            int c = 0;
            for (byte x : b) c = (c << 8) | (x & 255);
            return c;
        }

        private static String dst(Object o) {
            if (o instanceof Str) {
                byte[] b = ((Str) o).b;
                if (b.length == 1) return new String(new char[]{(char) (b[0] & 255)});
                return new String(b, Charset.forName("UTF-16BE"));
            }
            if (o instanceof Name) return glyph(((Name) o).v);
            return null;
        }

        String toText(int code) {
            if (uni != null) {
                String s = uni.get(code);
                if (s != null) return s;
            }
            if (composite) return null;
            return code >= 0 && code < 256 ? enc[code] : null;
        }

        double width(int code) {
            if (composite) {
                Double w = cw == null ? null : cw.get(code);
                return w != null ? w : dw;
            }
            if (widths != null && code >= first && code - first < widths.length) return widths[code - first];
            return missing;
        }
    }

    // ------------------------------------------------------------------ glyph names

    private static final Map<String, Integer> GLYPHS = new HashMap<>();

    static {
        String t = "space:20 exclam:21 quotedbl:22 numbersign:23 dollar:24 percent:25 ampersand:26 quotesingle:27 "
            + "parenleft:28 parenright:29 asterisk:2A plus:2B comma:2C hyphen:2D period:2E slash:2F "
            + "zero:30 one:31 two:32 three:33 four:34 five:35 six:36 seven:37 eight:38 nine:39 colon:3A semicolon:3B "
            + "less:3C equal:3D greater:3E question:3F at:40 bracketleft:5B backslash:5C bracketright:5D "
            + "asciicircum:5E underscore:5F grave:60 braceleft:7B bar:7C braceright:7D asciitilde:7E "
            + "exclamdown:A1 cent:A2 sterling:A3 currency:A4 yen:A5 brokenbar:A6 section:A7 dieresis:A8 copyright:A9 "
            + "ordfeminine:AA guillemotleft:AB logicalnot:AC registered:AE macron:AF degree:B0 plusminus:B1 "
            + "twosuperior:B2 threesuperior:B3 acute:B4 mu:B5 paragraph:B6 periodcentered:B7 cedilla:B8 onesuperior:B9 "
            + "ordmasculine:BA guillemotright:BB onequarter:BC onehalf:BD threequarters:BE questiondown:BF "
            + "Agrave:C0 Aacute:C1 Acircumflex:C2 Atilde:C3 Adieresis:C4 Aring:C5 AE:C6 Ccedilla:C7 Egrave:C8 Eacute:C9 "
            + "Ecircumflex:CA Edieresis:CB Igrave:CC Iacute:CD Icircumflex:CE Idieresis:CF Eth:D0 Ntilde:D1 Ograve:D2 "
            + "Oacute:D3 Ocircumflex:D4 Otilde:D5 Odieresis:D6 multiply:D7 Oslash:D8 Ugrave:D9 Uacute:DA Ucircumflex:DB "
            + "Udieresis:DC Yacute:DD Thorn:DE germandbls:DF agrave:E0 aacute:E1 acircumflex:E2 atilde:E3 adieresis:E4 "
            + "aring:E5 ae:E6 ccedilla:E7 egrave:E8 eacute:E9 ecircumflex:EA edieresis:EB igrave:EC iacute:ED "
            + "icircumflex:EE idieresis:EF eth:F0 ntilde:F1 ograve:F2 oacute:F3 ocircumflex:F4 otilde:F5 odieresis:F6 "
            + "divide:F7 oslash:F8 ugrave:F9 uacute:FA ucircumflex:FB udieresis:FC yacute:FD thorn:FE ydieresis:FF "
            + "endash:2013 emdash:2014 quoteleft:2018 quoteright:2019 quotesinglbase:201A quotedblleft:201C "
            + "quotedblright:201D quotedblbase:201E dagger:2020 daggerdbl:2021 bullet:2022 ellipsis:2026 "
            + "perthousand:2030 guilsinglleft:2039 guilsinglright:203A Euro:20AC trademark:2122 fi:FB01 fl:FB02 "
            + "minus:2212 fraction:2044 OE:152 oe:153 Scaron:160 scaron:161 Zcaron:17D zcaron:17E Ydieresis:178 "
            + "florin:192 circumflex:2C6 tilde:2DC nbspace:A0 nonbreakingspace:A0 sfthyphen:AD";
        for (String e : t.split(" ")) {
            int i = e.indexOf(':');
            GLYPHS.put(e.substring(0, i), Integer.parseInt(e.substring(i + 1), 16));
        }
    }

    static String glyph(String n) {
        if (n.length() == 1) return n;
        Integer c = GLYPHS.get(n);
        if (c != null) return new String(Character.toChars(c));
        try {
            if (n.startsWith("uni") && n.length() >= 7) return new String(Character.toChars(Integer.parseInt(n.substring(3, 7), 16)));
            if (n.startsWith("u") && n.length() >= 5 && n.length() <= 7) return new String(Character.toChars(Integer.parseInt(n.substring(1), 16)));
        } catch (RuntimeException e) {
            return null;
        }
        int dot = n.indexOf('.');
        if (dot > 0) return glyph(n.substring(0, dot));
        return null;
    }

    // ------------------------------------------------------------------ content stream interpreter

    static final class State {
        double[] ctm;
        Font font;
        double fs, tc, tw, th = 1, tl, rise;

        State copy() {
            State s = new State();
            s.ctm = ctm.clone();
            s.font = font;
            s.fs = fs;
            s.tc = tc;
            s.tw = tw;
            s.th = th;
            s.tl = tl;
            s.rise = rise;
            return s;
        }
    }

    static double[] mul(double[] a, double[] b) {
        return new double[]{
            a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
            a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]
        };
    }

    static final class Interp {
        final Doc doc;
        final List<Item> items;
        State st;
        final List<State> stack = new ArrayList<>();
        double[] tm = {1, 0, 0, 1, 0, 0}, tlm = {1, 0, 0, 1, 0, 0};
        int formBudget = 200;

        Interp(Doc d, List<Item> items) {
            doc = d;
            this.items = items;
        }

        void run(byte[] content, Map<String, Object> res, double[] ctm, int depth) {
            State saved = st;
            List<State> savedStack = new ArrayList<>(stack);
            st = new State();
            st.ctm = ctm;
            stack.clear();
            Map<String, Object> fontRes = res == null ? null : doc.dictOf(res.get("Font"));
            Map<String, Object> xobjRes = res == null ? null : doc.dictOf(res.get("XObject"));
            Lexer lx = new Lexer(content, 0, content.length);
            List<Object> a = new ArrayList<>();
            while (true) {
                Object t = lx.next(false);
                if (t == EOF) break;
                if (!(t instanceof Op)) {
                    a.add(t);
                    if (a.size() > 512) a.clear();
                    continue;
                }
                String op = ((Op) t).v;
                try {
                    switch (op) {
                        case "q": stack.add(st.copy()); break;
                        case "Q": if (!stack.isEmpty()) st = stack.remove(stack.size() - 1); break;
                        case "cm":
                            if (a.size() >= 6) st.ctm = mul(nums(a, 6), st.ctm);
                            break;
                        case "BT":
                            tm = new double[]{1, 0, 0, 1, 0, 0};
                            tlm = tm.clone();
                            break;
                        case "Tf":
                            if (a.size() >= 2 && a.get(a.size() - 2) instanceof Name) {
                                String fn = ((Name) a.get(a.size() - 2)).v;
                                st.fs = num(a.get(a.size() - 1));
                                st.font = fontRes == null ? null : doc.font(fontRes.get(fn));
                            }
                            break;
                        case "Tc": st.tc = num(last(a)); break;
                        case "Tw": st.tw = num(last(a)); break;
                        case "Tz": st.th = num(last(a)) / 100.0; break;
                        case "TL": st.tl = num(last(a)); break;
                        case "Ts": st.rise = num(last(a)); break;
                        case "Td":
                            if (a.size() >= 2) td(num(a.get(a.size() - 2)), num(a.get(a.size() - 1)));
                            break;
                        case "TD":
                            if (a.size() >= 2) {
                                st.tl = -num(a.get(a.size() - 1));
                                td(num(a.get(a.size() - 2)), num(a.get(a.size() - 1)));
                            }
                            break;
                        case "Tm":
                            if (a.size() >= 6) {
                                tm = nums(a, 6);
                                tlm = tm.clone();
                            }
                            break;
                        case "T*": td(0, -st.tl); break;
                        case "Tj":
                            if (last(a) instanceof Str) show(((Str) last(a)).b);
                            break;
                        case "'":
                            td(0, -st.tl);
                            if (last(a) instanceof Str) show(((Str) last(a)).b);
                            break;
                        case "\"":
                            if (a.size() >= 3) {
                                st.tw = num(a.get(a.size() - 3));
                                st.tc = num(a.get(a.size() - 2));
                            }
                            td(0, -st.tl);
                            if (last(a) instanceof Str) show(((Str) last(a)).b);
                            break;
                        case "TJ":
                            if (last(a) instanceof List) {
                                for (Object e : (List<?>) last(a)) {
                                    if (e instanceof Str) show(((Str) e).b);
                                    else if (e instanceof Double) {
                                        double tx = -((Double) e) / 1000.0 * st.fs * st.th;
                                        tm[4] += tx * tm[0];
                                        tm[5] += tx * tm[1];
                                    }
                                }
                            }
                            break;
                        case "Do":
                            if (depth < 5 && formBudget > 0 && xobjRes != null && last(a) instanceof Name) {
                                Object xo = doc.resolve(xobjRes.get(((Name) last(a)).v));
                                if (xo instanceof Stm && Doc.isName(((Stm) xo).dict.get("Subtype"), "Form")) {
                                    formBudget--;
                                    Stm fm = (Stm) xo;
                                    List<Object> ml = doc.listOf(fm.dict.get("Matrix"));
                                    double[] m = {1, 0, 0, 1, 0, 0};
                                    if (ml != null && ml.size() >= 6) m = nums(new ArrayList<>(ml), 6);
                                    Map<String, Object> fr = doc.dictOf(fm.dict.get("Resources"));
                                    double[] saveTm = tm, saveTlm = tlm;
                                    State keep = st;
                                    List<State> keepStack = new ArrayList<>(stack);
                                    run(doc.decode(fm), fr != null ? fr : res, mul(m, st.ctm), depth + 1);
                                    st = keep;
                                    stack.clear();
                                    stack.addAll(keepStack);
                                    tm = saveTm;
                                    tlm = saveTlm;
                                }
                            }
                            break;
                        case "BI": {
                            Object k;
                            do {
                                k = lx.next(false);
                            } while (k != EOF && !(k instanceof Op && ((Op) k).v.equals("ID")));
                            lx.p++;
                            while (lx.p + 1 < lx.end) {
                                if (Lexer.isWs(content[lx.p] & 255) && content[lx.p + 1] == 'E' && lx.p + 2 < lx.end + 0
                                    && content[lx.p + 2] == 'I'
                                    && (lx.p + 3 >= lx.end || Lexer.isWs(content[lx.p + 3] & 255))) {
                                    lx.p += 3;
                                    break;
                                }
                                lx.p++;
                            }
                            break;
                        }
                        default: break;
                    }
                } catch (IOException | RuntimeException e) {
                    // ignore a bad operator
                }
                a.clear();
            }
            st = saved;
            stack.clear();
            stack.addAll(savedStack);
        }

        private static Object last(List<Object> a) { return a.isEmpty() ? null : a.get(a.size() - 1); }

        private static double num(Object o) { return o instanceof Double ? (Double) o : 0; }

        private static double[] nums(List<Object> a, int n) {
            double[] r = new double[n];
            for (int i = 0; i < n; i++) r[i] = num(a.get(a.size() - n + i));
            return r;
        }

        private void td(double tx, double ty) {
            tlm = mul(new double[]{1, 0, 0, 1, tx, ty}, tlm);
            tm = tlm.clone();
        }

        private void show(byte[] s) {
            Font f = st.font;
            if (f == null || s.length == 0) return;
            double[] full = mul(tm, st.ctm);
            double sx = tm[4] + st.rise * tm[2], sy = tm[5] + st.rise * tm[3];
            double x0 = sx * st.ctm[0] + sy * st.ctm[2] + st.ctm[4];
            double y0 = sx * st.ctm[1] + sy * st.ctm[3] + st.ctm[5];
            StringBuilder sb = new StringBuilder();
            int step = f.composite ? f.codeLen : 1;
            for (int i = 0; i < s.length; i += step) {
                int code = 0;
                for (int k = 0; k < step && i + k < s.length; k++) code = (code << 8) | (s[i + k] & 255);
                String t = f.toText(code);
                if (t != null) sb.append(t);
                else if (code != 0) doc.unmapped++;
                double w = f.width(code) / 1000.0;
                double tx = (w * st.fs + st.tc + ((step == 1 && code == 32) ? st.tw : 0)) * st.th;
                tm[4] += tx * tm[0];
                tm[5] += tx * tm[1];
            }
            double ex = tm[4] + st.rise * tm[2], ey = tm[5] + st.rise * tm[3];
            double x1 = ex * st.ctm[0] + ey * st.ctm[2] + st.ctm[4];
            if (sb.length() == 0) return;
            Item it = new Item();
            it.x0 = x0;
            it.x1 = x1;
            it.y = y0;
            it.fs = Math.abs(st.fs) * Math.hypot(full[2], full[3]);
            if (it.fs < 1) it.fs = Math.abs(st.fs) > 0 ? Math.abs(st.fs) : 10;
            it.text = sb.toString();
            it.bold = f.bold;
            if (items.size() < 400000) items.add(it);
        }
    }

    // ------------------------------------------------------------------ lines and paragraphs

    static final class Line {
        String text;
        double x0, x1, y, fs;
        boolean bold;
    }

    private static Line makeLine(List<Item> cur) {
        Collections.sort(cur, (a, b) -> Double.compare(a.x0, b.x0));
        StringBuilder sb = new StringBuilder();
        Line ln = new Line();
        ln.x0 = cur.get(0).x0;
        ln.y = cur.get(0).y;
        double x1 = cur.get(0).x1;
        int boldChars = 0, chars = 0, best = -1;
        Item prev = null;
        for (Item it : cur) {
            if (prev != null) {
                double gap = it.x0 - prev.x1;
                double fs = Math.max(prev.fs, 1);
                boolean spaceBefore = prev.text.endsWith(" ") || it.text.startsWith(" ");
                if (gap > 2.5 * fs) sb.append('\t');
                else if (!spaceBefore && gap > 0.15 * fs) sb.append(' ');
            }
            sb.append(it.text);
            x1 = Math.max(x1, it.x1);
            int n = it.text.length();
            chars += n;
            if (it.bold) boldChars += n;
            if (n > best) {
                best = n;
                ln.fs = it.fs;
            }
            prev = it;
        }
        ln.text = sb.toString().trim();
        ln.x1 = x1;
        ln.bold = boldChars * 2 > chars;
        return ln;
    }

    private static void emitPage(List<Item> items, Out out, boolean[] emitted) throws IOException {
        if (items.isEmpty()) return;
        Collections.sort(items, (a, b) -> {
            int c = Double.compare(b.y, a.y);
            return c != 0 ? c : Double.compare(a.x0, b.x0);
        });
        List<Line> lines = new ArrayList<>();
        List<Item> cur = new ArrayList<>();
        double refY = 0;
        for (Item it : items) {
            if (!cur.isEmpty() && refY - it.y > 0.45 * Math.max(it.fs, 1)) {
                lines.add(makeLine(cur));
                cur = new ArrayList<>();
            }
            if (cur.isEmpty()) refY = it.y;
            cur.add(it);
        }
        if (!cur.isEmpty()) lines.add(makeLine(cur));

        List<Line> keep = new ArrayList<>();
        for (Line l : lines) if (!l.text.isEmpty()) keep.add(l);
        if (keep.isEmpty()) return;

        double minLeft = Double.MAX_VALUE, maxRight = -Double.MAX_VALUE;
        for (Line l : keep) {
            minLeft = Math.min(minLeft, l.x0);
            maxRight = Math.max(maxRight, l.x1);
        }
        double full = minLeft + 0.8 * (maxRight - minLeft);

        boolean newPage = emitted[0];
        StringBuilder para = null;
        Line head = null, prev = null;
        for (Line l : keep) {
            boolean merge = false;
            if (prev != null) {
                double gap = prev.y - l.y;
                merge = Math.abs(prev.fs - l.fs) < 0.6 && prev.bold == l.bold
                    && gap > 0 && gap < 1.7 * prev.fs
                    && prev.x1 >= full && prev.text.indexOf('\t') < 0
                    && l.x0 <= prev.x0 + 0.5 * prev.fs;
            }
            if (merge) {
                String t = l.text;
                int n = para.length();
                if (n > 1 && para.charAt(n - 1) == '-' && Character.isLowerCase(t.charAt(0))
                    && Character.isLetter(para.charAt(n - 2))) {
                    para.setLength(n - 1);
                } else {
                    para.append(' ');
                }
                para.append(t);
            } else {
                if (para != null) {
                    flush(out, para, head, newPage);
                    newPage = false;
                    emitted[0] = true;
                }
                para = new StringBuilder(l.text);
                head = l;
            }
            prev = l;
        }
        if (para != null) {
            flush(out, para, head, newPage);
            emitted[0] = true;
        }
    }

    private static void flush(Out out, StringBuilder para, Line head, boolean newPage) throws IOException {
        float size = (float) (Math.round(head.fs * 2) / 2.0);
        size = Math.max(6f, Math.min(60f, size));
        out.paragraph(para.toString(), size, head.bold, newPage);
    }
}
