package com.litereader;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** An image-page book: an EPUB whose spine pages are images, or any zip (CBZ) of images. Pages are random-access zip entries. */
final class Book {
    static class Unsupported extends IOException { Unsupported(String m) { super(m); } }

    static final int VER = 4;
    static class NotImages extends Unsupported { NotImages() { super("No image pages found. This looks like a text book."); } }

    final File file;          // file the pages are read from (the original, or a converted EPUB in the app cache)
    final File orig;          // the file the user picked
    String key;
    final String title;
    int n;
    boolean textHeavy, isEpubBook, fromMobi;
    String coverHref;
    long[] off; int[] csz, usz, comp;
    long[] raw;               // image MOBI: absolute data offset of each page record (no zip header)
    long mu;                  // MuPDF document handle (PDF, text EPUB, TXT), 0 for image books
    int dirGuess;             // detected reading direction: 0 = unknown, 1 = manga (RTL), 2 = normal (LTR)

    Book(File f, File original) {
        file = f; orig = original; key = Store.key(original);
        String nm = original.getName(); int d = nm.lastIndexOf('.');
        title = d > 0 ? nm.substring(0, d) : nm;
    }

    static boolean isMobi(String l) { return l.endsWith(".mobi") || l.endsWith(".azw") || l.endsWith(".azw3") || l.endsWith(".prc"); }

    /** types LiteReader opens (or explains): shown in the lists */
    static boolean supportedName(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".epub") || l.endsWith(".cbz") || l.endsWith(".pdf") || l.endsWith(".txt") || l.endsWith(".docx") || isMobi(l);
    }

    /** known formats that cannot be opened: listed in the explorer with a reason, never crash */
    static String unsupportedMessage(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(".kfx") || l.endsWith(".kfx-zip")) return "KFX books (Kindle Format 10) are not supported. Use an EPUB or AZW3 without DRM.";
        if (l.endsWith(".azw4")) return "AZW4 (Kindle print replica) is not supported. Use the PDF or an EPUB instead.";
        if (l.endsWith(".djvu") || l.endsWith(".djv")) return "DjVu is not supported. Convert it to PDF or CBZ (for example with Calibre or djvulibre).";
        if (l.endsWith(".chm")) return "CHM is not supported. Convert it to EPUB with Calibre.";
        if (l.endsWith(".doc") || l.endsWith(".odt") || l.endsWith(".rtf") || l.endsWith(".xlsx") || l.endsWith(".pptx")) return "Only Word .docx documents are supported. Export this file to DOCX, PDF or EPUB first.";
        return null;
    }

    static boolean listedName(String name) { return supportedName(name) || unsupportedMessage(name) != null; }

    static final String TAG = "LiteReader";

    static Book open(Context ctx, File f) throws IOException {
        if (f.length() > 0x7FFFFFFFL) throw new Unsupported("This file is larger than 2 GB. LiteReader cannot open files over 2 GB.");
        String l = f.getName().toLowerCase();
        if (l.endsWith(".txt") && f.length() > MAX_TXT) throw new Unsupported("This text file is " + (f.length() >> 20) + " MB. LiteReader opens TXT files up to " + (MAX_TXT >> 20) + " MB (larger ones need more memory than this tablet has). Split it, or convert it to EPUB with chapters.");
        if (l.endsWith(".docx") && f.length() > MAX_DOCX) throw new Unsupported("This Word document is " + (f.length() >> 20) + " MB. LiteReader opens DOCX files up to " + (MAX_DOCX >> 20) + " MB. Export it to PDF and open that instead.");
        if (l.endsWith(".pdf") || l.endsWith(".txt") || l.endsWith(".docx")) return openMu(f, f);
        if (isMobi(l)) return openMobi(ctx, f);
        String um = unsupportedMessage(l);
        if (um != null) throw new Unsupported(um);
        return openZip(ctx, f, f, l.endsWith(".epub"));
    }

    /** zip based books: image EPUB / CBZ through the image engine; EPUB that is mostly text goes to MuPDF */
    static Book openZip(Context ctx, File src, File orig, boolean epub) throws IOException { return openZip(ctx, src, orig, epub, false); }

    static Book openZip(Context ctx, File src, File orig, boolean epub, boolean fromMobi) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        Book b = new Book(src, orig);
        b.fromMobi = fromMobi;
        File cache = new File(ctx.getFilesDir(), "idx_" + Integer.toHexString(b.key.hashCode()) + "_" + src.lastModified() + ".bin");
        int st = cache.isFile() ? b.loadCache(cache) : 0;
        if (st == 1) { Log.i(TAG, "index from cache: " + b.n + " pages in " + (SystemClock.elapsedRealtime() - t0) + " ms"); return b; }
        if (st == -1) { if (epub) return openMu(src, orig); throw new NotImages(); }
        try {
            b.build();
        } catch (NotImages e) {
            if (!epub) throw e;
            b.markText(cache);
            return openMu(src, orig);
        }
        b.saveCache(cache);
        Log.i(TAG, "index built: " + b.n + " pages in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        return b;
    }

    static Book openMu(File src, File orig) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        if (!NativeMu.load()) throw new Unsupported("The text/PDF engine could not be loaded on this device.");
        Book b = new Book(src, orig);
        if (src.getName().toLowerCase().endsWith(".docx")) {           // tested up to 9.1 MB of document text (223 MB peak)
            RandomAccessFile rf = new RandomAccessFile(src, "r");
            try {
                for (Ent e : b.readDirectory(rf))
                    if (e.name.equals("word/document.xml") && e.usz > MAX_DOCX_XML)
                        throw new Unsupported("The text of this Word document is " + (e.usz >> 20) + " MB (limit " + (MAX_DOCX_XML >> 20) + " MB): the tablet cannot lay it out safely. Export it to PDF and open that instead.");
            } catch (Unsupported u) { throw u; } catch (IOException io) { throw new Unsupported("This is not a valid Word .docx file."); }
            finally { rf.close(); }
        }
        if (src.getName().toLowerCase().endsWith(".epub")) {          // one giant chapter makes MuPDF load all of it: refuse before Android kills the app
            RandomAccessFile rf = new RandomAccessFile(src, "r");
            try {
                for (Ent e : b.readDirectory(rf)) {
                    String nl = e.name.toLowerCase();
                    if ((nl.endsWith(".html") || nl.endsWith(".xhtml") || nl.endsWith(".htm") || nl.endsWith(".xml")) && e.usz > MAX_HTML_PART)
                        throw new Unsupported("This book has a single text section of " + (e.usz >> 20) + " MB (limit " + (MAX_HTML_PART >> 20) + " MB per chapter): the tablet cannot lay it out safely. Split it into chapters (for example convert it to EPUB with Calibre) and try again.");
                }
            } finally { rf.close(); }
        }
        b.key = Store.key(orig) + "|mu1";
        long h = NativeMu.open(src.getAbsolutePath(), 360, 576, 11f);
        if (h == 0) {
            String m = NativeMu.lastError();
            throw new Unsupported(m != null && m.contains("password") ? "This document is password protected." : "Cannot open this document" + (m != null && m.length() > 0 ? " (" + m + ")" : "."));
        }
        b.mu = h; b.n = NativeMu.pageCount(h);
        Log.i(TAG, "mupdf open: " + b.n + " pages in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        return b;
    }

    // ---- MOBI / AZW / AZW3 ----
    static final long MAX_TXT = 10L << 20, MAX_DOCX = 15L << 20, MAX_DOCX_XML = 12L << 20, MAX_HTML_PART = 10L << 20;     // measured: a 20 MB TXT peaks at 420 MB, a 50 MB single-block book is killed by Android
    static final long MAX_CONVERT = 150L << 20;      // libmobi loads the whole file into RAM; measured limit, see REPORT.md

    static Book openMobi(Context ctx, File f) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] h = new byte[78];
            r.readFully(h);
            String type = new String(h, 60, 8, "ISO-8859-1");
            if (!type.equals("BOOKMOBI") && !type.equals("TEXtREAd")) throw new Unsupported("This is not a MOBI/AZW book (unknown file type " + type.trim() + ").");
            int nrec = ((h[76] & 255) << 8) | (h[77] & 255);
            if (nrec < 2) throw new Unsupported("Empty MOBI file.");
            byte[] rt = new byte[nrec * 8];
            r.readFully(rt);
            long[] offs = new long[nrec + 1];
            for (int i = 0; i < nrec; i++) offs[i] = le32be(rt, i * 8);
            offs[nrec] = f.length();
            int r0len = (int) Math.min(offs[1] - offs[0], 4096);
            byte[] r0 = new byte[r0len];
            r.seek(offs[0]); r.readFully(r0);
            int comp = be16(r0, 0), crypt = be16(r0, 12);
            long textLen = le32be(r0, 4);
            int textRecs = be16(r0, 8);
            if (crypt != 0) throw new Unsupported("This book is DRM-protected (encrypted). LiteReader can only open books without DRM.");
            int ver = r0len > 40 && r0[16] == 'M' && r0[17] == 'O' && r0[18] == 'B' && r0[19] == 'I' ? (int) le32be(r0, 36) : 0;
            if (r0len > 8 && new String(r0, 16, 4, "ISO-8859-1").equals("CONT")) throw new Unsupported("KFX books are not supported.");
            int firstImg = ver > 0 && r0len > 112 ? (int) le32be(r0, 108) : -1;
            int images = firstImg > 0 && firstImg < nrec ? countImages(r, offs, firstImg, nrec) : 0;
            Log.i(TAG, "mobi: version " + ver + " comp " + comp + " textLen " + textLen + " textRecs " + textRecs + " images " + images);
            if (ver > 0 && ver < 8 && images >= 10 && textLen < images * 4000L && (comp == 1 || comp == 2)) {
                Book b = mobi6Comic(r, f, offs, r0, comp, textRecs, firstImg, nrec);
                if (b != null) { Log.i(TAG, "mobi6 comic index: " + b.n + " pages in " + (SystemClock.elapsedRealtime() - t0) + " ms"); return b; }
            }
        } finally { r.close(); }
        // everything else: libmobi converts to EPUB (cached); the EPUB then takes the normal image or text route
        if (f.length() > MAX_CONVERT) throw new Unsupported("This MOBI/AZW file is " + (f.length() >> 20) + " MB, too large to convert on this tablet (limit " + (MAX_CONVERT >> 20) + " MB). Convert it to EPUB with Calibre on a computer.");
        if (!NativeMu.load()) throw new Unsupported("The MOBI engine could not be loaded on this device.");
        File cv = new File(ctx.getFilesDir(), "cv_" + Integer.toHexString(Store.key(f).hashCode()) + "_" + f.lastModified() + ".epub");
        if (!cv.isFile() || cv.length() == 0) {
            for (File old : ctx.getFilesDir().listFiles()) if (old.getName().startsWith("cv_" + Integer.toHexString(Store.key(f).hashCode()))) old.delete();
            int rc = NativeMu.mobiToEpub(f.getAbsolutePath(), cv.getAbsolutePath());
            if (rc != 0) {
                String m = NativeMu.mobiError();
                if (rc == 1) throw new Unsupported("This book is DRM-protected. LiteReader can only open MOBI/AZW books without DRM.");
                if (rc == 2) throw new Unsupported("AZW4 (Kindle print replica) is not supported.");
                if (rc == 5) throw new Unsupported("Not enough memory to convert this book.");
                throw new Unsupported("Could not convert this MOBI/AZW file" + (m != null && m.length() > 0 ? " (" + m + ")" : "") + ".");
            }
            Log.i(TAG, "mobi converted in " + (SystemClock.elapsedRealtime() - t0) + " ms, " + (cv.length() >> 10) + " KB epub");
        } else Log.i(TAG, "mobi converted epub from cache");
        return openZip(ctx, cv, f, true, true);
    }

    static int be16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
    static long le32be(byte[] b, int o) { return ((b[o] & 255L) << 24) | ((b[o + 1] & 255L) << 16) | ((b[o + 2] & 255L) << 8) | (b[o + 3] & 255L); }

    static boolean isImageHead(byte[] h) {
        return (h[0] == (byte) 0xFF && h[1] == (byte) 0xD8) || (h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G');
    }

    static int countImages(RandomAccessFile r, long[] offs, int first, int nrec) throws IOException {
        byte[] h = new byte[4]; int c = 0;
        for (int i = first; i < nrec; i++) {
            if (offs[i + 1] - offs[i] < 4) continue;
            r.seek(offs[i]); r.readFully(h);
            if (isImageHead(h)) c++;
        }
        return c;
    }

    /** MOBI 6 image book: page order is the order of <img recindex="N"> in the (small) text; records are read in place. */
    static Book mobi6Comic(RandomAccessFile r, File f, long[] offs, byte[] r0, int comp, int textRecs, int firstImg, int nrec) throws IOException {
        int flags = r0.length > 244 ? be16(r0, 242) : 0;
        java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream((int) Math.min(textRecsSize(offs, textRecs), 8 << 20));
        for (int i = 1; i <= textRecs && i < nrec; i++) {
            int len = (int) (offs[i + 1] - offs[i]);
            if (len <= 0 || len > (1 << 20)) return null;
            byte[] rec = new byte[len];
            r.seek(offs[i]); r.readFully(rec);
            len = trimTrailing(rec, len, flags);
            if (comp == 1) text.write(rec, 0, len); else palmdoc(rec, len, text);
        }
        String t = new String(text.toByteArray(), "ISO-8859-1");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("recindex=[\"']?(\\d+)").matcher(t);
        ArrayList<Integer> pages = new ArrayList<Integer>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
        byte[] hd = new byte[4];
        while (m.find()) {
            int idx = firstImg + Integer.parseInt(m.group(1)) - 1;
            if (idx <= 0 || idx >= nrec) continue;
            if (offs[idx + 1] - offs[idx] < 4) continue;
            r.seek(offs[idx]); r.readFully(hd);
            if (isImageHead(hd)) pages.add(idx);
        }
        int expect = countImages(r, offs, firstImg, nrec);
        if (pages.size() < expect * 0.9) return null;          // text order not reliable: let libmobi handle it
        int cover = exthCover(r0);
        if (cover >= 0 && firstImg + cover < nrec) {
            int ci = firstImg + cover;
            if (!pages.contains(ci) && offs[ci + 1] - offs[ci] > 4) { r.seek(offs[ci]); r.readFully(hd); if (isImageHead(hd)) pages.add(0, ci); }
        }
        Book b = new Book(f, f);
        b.n = pages.size();
        b.off = new long[b.n]; b.raw = new long[b.n]; b.csz = new int[b.n]; b.usz = new int[b.n]; b.comp = new int[b.n];
        StringBuilder fr = new StringBuilder();
        for (int i = 0; i < Math.min(8, pages.size()); i++) fr.append(pages.get(i)).append(' ');
        Log.i(TAG, "mobi6 comic pages " + pages.size() + ", first records: " + fr);
        for (int i = 0; i < b.n; i++) {
            int rec = pages.get(i);
            b.off[i] = offs[rec]; b.raw[i] = offs[rec]; b.csz[i] = (int) (offs[rec + 1] - offs[rec]); b.usz[i] = b.csz[i]; b.comp[i] = 0;
        }
        return b;
    }

    /** EXTH record 201 = cover image offset (relative to the first image record), or -1 */
    static int exthCover(byte[] r0) {
        if (r0.length < 132) return -1;
        long hl = le32be(r0, 20);
        int p = (int) (16 + hl);
        if (p + 12 > r0.length || r0[p] != 'E' || r0[p + 1] != 'X' || r0[p + 2] != 'T' || r0[p + 3] != 'H') return -1;
        long cnt = le32be(r0, p + 8); p += 12;
        for (int i = 0; i < cnt && p + 8 <= r0.length; i++) {
            long type = le32be(r0, p), len = le32be(r0, p + 4);
            if (len < 8) break;
            if (type == 201 && len == 12 && p + 12 <= r0.length) return (int) le32be(r0, p + 8);
            p += (int) len;
        }
        return -1;
    }

    static long textRecsSize(long[] offs, int textRecs) { return offs[Math.min(textRecs + 1, offs.length - 1)] - offs[1]; }

    /** removes the trailing entries (multibyte overlap, indexing data) that follow the compressed text in each record */
    static int trimTrailing(byte[] rec, int len, int flags) {
        for (int bit = 1; bit < 16; bit++) {
            if ((flags & (1 << bit)) == 0) continue;
            int size = 0, shift = 0, pos = len - 1;
            while (pos >= 0) {
                int b = rec[pos] & 255;
                size |= (b & 0x7f) << shift; shift += 7; pos--;
                if ((b & 0x80) != 0 || shift > 28) break;
            }
            if (size <= 0 || size > len) break;
            len -= size;
        }
        if ((flags & 1) != 0 && len > 0) len -= (rec[len - 1] & 3) + 1;
        return Math.max(len, 0);
    }

    static void palmdoc(byte[] in, int len, java.io.ByteArrayOutputStream out) {
        byte[] o = new byte[Math.max(len * 8, 4096)];
        int op = 0, i = 0;
        while (i < len) {
            int c = in[i++] & 255;
            if (op + 16 > o.length) o = java.util.Arrays.copyOf(o, o.length * 2);
            if (c == 0 || (c >= 9 && c <= 0x7f)) o[op++] = (byte) c;
            else if (c <= 8) { for (int k = 0; k < c && i < len; k++) o[op++] = in[i++]; }
            else if (c >= 0xc0) { o[op++] = ' '; o[op++] = (byte) (c ^ 0x80); }
            else {
                if (i >= len) break;
                int d = (c << 8) | (in[i++] & 255);
                int dist = (d >> 3) & 0x7ff, n = (d & 7) + 3;
                if (dist == 0 || dist > op) break;
                if (op + n + 16 > o.length) o = java.util.Arrays.copyOf(o, o.length * 2);
                for (int k = 0; k < n; k++) { o[op] = o[op - dist]; op++; }
            }
        }
        out.write(o, 0, op);
    }

    // ---- cache ----
    /** 1 = loaded, 0 = no usable cache, -1 = cached verdict: text book */
    int loadCache(File c) {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(c), 65536));
            try {
                if (in.readInt() != VER || in.readLong() != file.length()) return 0;
                int cnt = in.readInt();
                if (cnt == 0) return -1;
                if (cnt < 0 || cnt > 2000000) return 0;
                long[] o = new long[cnt]; int[] cs = new int[cnt], us = new int[cnt], cm = new int[cnt];
                for (int i = 0; i < cnt; i++) { o[i] = in.readLong(); cs[i] = in.readInt(); us[i] = in.readInt(); cm[i] = in.readByte(); }
                n = cnt; off = o; csz = cs; usz = us; comp = cm;
                return 1;
            } finally { in.close(); }
        } catch (IOException e) { return 0; }
    }

    void markText(File c) {
        try {
            DataOutputStream out = new DataOutputStream(new FileOutputStream(c));
            try { out.writeInt(VER); out.writeLong(file.length()); out.writeInt(0); } finally { out.close(); }
        } catch (IOException e) { c.delete(); }
    }

    void saveCache(File c) {
        try {
            DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(c), 65536));
            try {
                out.writeInt(VER); out.writeLong(file.length()); out.writeInt(n);
                for (int i = 0; i < n; i++) { out.writeLong(off[i]); out.writeInt(csz[i]); out.writeInt(usz[i]); out.writeByte(comp[i]); }
            } finally { out.close(); }
        } catch (IOException e) { c.delete(); }
    }

    // ---- zip helpers ----
    static int le16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    static long le32(byte[] b, int o) { return (b[o] & 255L) | ((b[o + 1] & 255L) << 8) | ((b[o + 2] & 255L) << 16) | ((b[o + 3] & 255L) << 24); }

    static class Ent { String name; long off; int csz, usz, comp; }

    ArrayList<Ent> readDirectory(RandomAccessFile f) throws IOException {
        long len = f.length();
        int tail = (int) Math.min(65536, len);
        byte[] t = new byte[tail];
        f.seek(len - tail); f.readFully(t);
        int e = tail - 22;
        while (e >= 0 && !(t[e] == 0x50 && t[e + 1] == 0x4b && t[e + 2] == 5 && t[e + 3] == 6)) e--;
        if (e < 0) throw new Unsupported("Not a zip/EPUB/CBZ file");
        long cdSize = le32(t, e + 12), cdOff = le32(t, e + 16);
        if (cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL || le16(t, e + 10) == 0xFFFF) throw new Unsupported("ZIP64 archives are not supported");
        if (len > 0x7FFFFFFFL) throw new Unsupported("Files over 2 GB are not supported");
        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOff); f.readFully(cd);
        ArrayList<Ent> list = new ArrayList<Ent>();
        int pos = 0;
        while (pos + 46 <= cd.length && le32(cd, pos) == 0x02014b50L) {
            int nl = le16(cd, pos + 28), xl = le16(cd, pos + 30), cl = le16(cd, pos + 32);
            Ent en = new Ent();
            en.name = new String(cd, pos + 46, nl, "UTF-8");
            en.comp = le16(cd, pos + 10); en.csz = (int) le32(cd, pos + 20); en.usz = (int) le32(cd, pos + 24); en.off = le32(cd, pos + 42);
            list.add(en);
            pos += 46 + nl + xl + cl;
        }
        return list;
    }

    /** Reads the direction the book declares: EPUB spine page-progression-direction, KF8 primary-writing-mode, CBZ ComicInfo.xml. Unknown stays 0. */
    int detectDirection() {
        if (raw != null || mu != 0) return 0;
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(file, "r");
            ArrayList<Ent> ents = readDirectory(f);
            HashMap<String, Integer> byName = new HashMap<String, Integer>();
            for (int i = 0; i < ents.size(); i++) byName.put(ents.get(i).name, i);
            Integer ci = byName.get("ComicInfo.xml");
            if (ci != null) {
                byte[] x = readSmall(f, ents.get(ci));
                if (x != null) {
                    String s = new String(x, "UTF-8");
                    int a = s.indexOf("<Manga>");
                    if (a >= 0) {
                        String v = s.substring(a + 7, Math.min(s.length(), a + 40)).toLowerCase();
                        if (v.startsWith("yesandrighttoleft")) return 1;
                        if (v.startsWith("no")) return 2;
                    }
                }
            }
            if (!byName.containsKey("META-INF/container.xml")) return 0;
            byte[] cx = readSmall(f, ents.get(byName.get("META-INF/container.xml")));
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(cx), null);
            String opfPath = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next())
                if (ev == XmlPullParser.START_TAG && p.getName().endsWith("rootfile")) { opfPath = attr(p, "full-path"); break; }
            if (opfPath == null) return 0;
            Integer oi = byName.get(resolve("", opfPath));
            if (oi == null) return 0;
            byte[] ox = readSmall(f, ents.get(oi));
            if (ox == null) return 0;
            p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(ox), null);
            int found = 0;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                String nm = p.getName(); int c = nm.lastIndexOf(':'); if (c >= 0) nm = nm.substring(c + 1);
                if (nm.equals("spine")) {
                    String d = attr(p, "page-progression-direction");
                    if ("rtl".equalsIgnoreCase(d)) return 1;
                    if ("ltr".equalsIgnoreCase(d)) found = 2;
                } else if (nm.equals("meta") && "primary-writing-mode".equals(attr(p, "name"))) {
                    String v = attr(p, "content");
                    if (v != null && v.toLowerCase().endsWith("-rl")) return 1;
                    if (v != null && v.toLowerCase().endsWith("-lr") && found == 0) found = 2;
                }
            }
            return found;
        } catch (Exception e) {
            return 0;
        } finally {
            try { if (f != null) f.close(); } catch (IOException ignored) {}
        }
    }

    static byte[] readSmall(RandomAccessFile f, Ent e) throws IOException {
        if (e.usz > (4 << 20)) return null;
        byte[] h = new byte[30];
        f.seek(e.off); f.readFully(h);
        long data = e.off + 30 + le16(h, 26) + le16(h, 28);
        byte[] c = new byte[e.csz];
        f.seek(data); f.readFully(c);
        if (e.comp == 0) return c;
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(c);
            byte[] o = new byte[e.usz];
            int got = 0;
            while (got < o.length) {
                int r = inf.inflate(o, got, o.length - got);
                if (r == 0 && (inf.finished() || inf.needsInput())) break;
                got += r;
            }
            return o;
        } catch (DataFormatException ex) { return null; }
        finally { inf.end(); }
    }

    static boolean isImage(String n) {
        String l = n.toLowerCase();
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg");
    }

    // ---- build ----
    void build() throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        try {
            ArrayList<Ent> ents = readDirectory(f);
            HashMap<String, Integer> byName = new HashMap<String, Integer>();
            for (int i = 0; i < ents.size(); i++) byName.put(ents.get(i).name, i);
            ArrayList<Integer> pages = null;
            isEpubBook = byName.containsKey("META-INF/container.xml");
            if (isEpubBook) {
                try { pages = spinePages(f, ents, byName); } catch (Exception ex) { pages = null; }
            }
            if (pages == null || pages.isEmpty()) {
                pages = new ArrayList<Integer>();
                final ArrayList<Ent> fe = ents;
                for (int i = 0; i < ents.size(); i++) {
                    String nm = ents.get(i).name;
                    if (isImage(nm) && !nm.contains("__MACOSX") && !nm.startsWith(".") && !nm.contains("/.")) pages.add(i);
                }
                Collections.sort(pages, new Comparator<Integer>() {
                    public int compare(Integer a, Integer b) { return natural(fe.get(a).name, fe.get(b).name); }
                });
            }
            if (pages.isEmpty() || (isEpubBook && textHeavy)) throw new NotImages();
            n = pages.size();
            off = new long[n]; csz = new int[n]; usz = new int[n]; comp = new int[n];
            for (int i = 0; i < n; i++) {
                Ent e = ents.get(pages.get(i));
                off[i] = e.off; csz[i] = e.csz; usz[i] = e.usz; comp[i] = e.comp;
                if (e.comp != 0 && e.comp != 8) throw new Unsupported("Unsupported zip compression");
            }
        } finally { f.close(); }
    }

    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                char lx = Character.toLowerCase(x), ly = Character.toLowerCase(y);
                if (lx != ly) return lx - ly;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    static String dir(String p) { int i = p.lastIndexOf('/'); return i < 0 ? "" : p.substring(0, i + 1); }

    static String resolve(String base, String href) {
        int h = href.indexOf('#'); if (h >= 0) href = href.substring(0, h);
        int q = href.indexOf('?'); if (q >= 0) href = href.substring(0, q);
        try { href = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { }
        String full = href.startsWith("/") ? href.substring(1) : base + href;
        String[] parts = full.split("/");
        ArrayList<String> st = new ArrayList<String>();
        for (String p : parts) {
            if (p.length() == 0 || p.equals(".")) continue;
            if (p.equals("..")) { if (!st.isEmpty()) st.remove(st.size() - 1); } else st.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.size(); i++) { if (i > 0) sb.append('/'); sb.append(st.get(i)); }
        return sb.toString();
    }

    static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String an = p.getAttributeName(i); int c = an.lastIndexOf(':');
            if ((c >= 0 ? an.substring(c + 1) : an).equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    int textItems;

    static int visibleText(String s) {
        int n = 0; boolean tag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') tag = true; else if (c == '>') tag = false; else if (!tag && c > ' ') n++;
        }
        return n;
    }

    static ArrayList<String> allImageRefs(String s) {
        ArrayList<String> out = new ArrayList<String>();
        int i = 0;
        while (true) {
            int a = s.indexOf("<img", i);
            if (a < 0) return out;
            int en = s.indexOf('>', a);
            if (en < 0) return out;
            String tag = s.substring(a, en);
            int k = tag.indexOf("src=");
            if (k >= 0) {
                char q = tag.charAt(k + 4);
                int e2 = (q == '"' || q == '\'') ? tag.indexOf(q, k + 5) : -1;
                if (e2 > 0) out.add(tag.substring(k + 5, e2));
            }
            i = en;
        }
    }

    static String firstImageRef(String s) {
        int i = 0;
        while (true) {
            int a = s.indexOf("<img", i), b = s.indexOf("<image", i);
            int st = a < 0 ? b : (b < 0 ? a : Math.min(a, b));
            if (st < 0) return null;
            int en = s.indexOf('>', st);
            if (en < 0) return null;
            String tag = s.substring(st, en);
            String[] names = {"xlink:href", "src", "href"};
            for (String nm : names) {
                int k = tag.indexOf(nm + "=");
                if (k < 0) continue;
                if (nm.equals("href") && k > 0 && tag.charAt(k - 1) == ':') continue;
                char q = tag.charAt(k + nm.length() + 1);
                if (q != '"' && q != '\'') continue;
                int e2 = tag.indexOf(q, k + nm.length() + 2);
                if (e2 > 0) return tag.substring(k + nm.length() + 2, e2);
            }
            i = en;
        }
    }

    ArrayList<Integer> spinePages(RandomAccessFile f, ArrayList<Ent> ents, HashMap<String, Integer> byName) throws Exception {
        byte[] cx = readSmall(f, ents.get(byName.get("META-INF/container.xml")));
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(cx), null);
        String opfPath = null;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next())
            if (ev == XmlPullParser.START_TAG && p.getName().endsWith("rootfile")) { opfPath = attr(p, "full-path"); break; }
        if (opfPath == null) return null;
        opfPath = resolve("", opfPath);
        Integer oi = byName.get(opfPath);
        if (oi == null) return null;
        byte[] ox = readSmall(f, ents.get(oi));
        String base = dir(opfPath);
        p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(ox), null);
        HashMap<String, String[]> manifest = new HashMap<String, String[]>();
        ArrayList<String> spine = new ArrayList<String>();
        String coverId = null;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev != XmlPullParser.START_TAG) continue;
            String nm = p.getName(); int c = nm.lastIndexOf(':'); if (c >= 0) nm = nm.substring(c + 1);
            if (nm.equals("meta") && "cover".equals(attr(p, "name"))) coverId = attr(p, "content");
            if (nm.equals("item")) {
                String id = attr(p, "id"), href = attr(p, "href"), mt = attr(p, "media-type");
                if (id != null && href != null) manifest.put(id, new String[]{href, mt == null ? "" : mt});
            } else if (nm.equals("itemref")) {
                String ir = attr(p, "idref");
                if (ir != null) spine.add(ir);
            }
        }
        if (coverId != null && manifest.get(coverId) != null) coverHref = manifest.get(coverId)[0];
        int m = spine.size();
        int[] result = new int[m];
        Arrays.fill(result, -1);
        HashMap<Integer, ArrayList<Integer>> multi = new HashMap<Integer, ArrayList<Integer>>();
        ArrayList<int[]> todo = new ArrayList<int[]>();   // {spineIdx, entryIdx}
        for (int i = 0; i < m; i++) {
            String[] it = manifest.get(spine.get(i));
            if (it == null) continue;
            Integer ei = byName.get(resolve(base, it[0]));
            if (ei == null) continue;
            if (it[1].startsWith("image/") || isImage(it[0])) result[i] = ei;
            else todo.add(new int[]{i, ei});
        }
        final ArrayList<Ent> fe = ents;
        Collections.sort(todo, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) { long x = fe.get(a[1]).off, y = fe.get(b[1]).off; return x < y ? -1 : (x > y ? 1 : 0); }
        });
        for (int[] t : todo) {
            Ent e = ents.get(t[1]);
            byte[] d = readSmall(f, e);
            if (d == null) continue;
            String xs = new String(d, "UTF-8");
            if (visibleText(xs) > 600) textItems++;
            String ref = firstImageRef(xs);
            if (ref == null) continue;
            Integer ii = byName.get(resolve(dir(e.name), ref));
            if (ii != null) result[t[0]] = ii;
            if (fromMobi) {                       // MOBI 6 books converted by libmobi keep every page in ONE xhtml
                ArrayList<Integer> more = new ArrayList<Integer>();
                for (String rf : allImageRefs(xs)) { Integer k = byName.get(resolve(dir(e.name), rf)); if (k != null) more.add(k); }
                if (more.size() > 1) multi.put(t[0], more);
            }
        }
        if (textItems * 4 > m) textHeavy = true;
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < m; i++) {
            ArrayList<Integer> mm = multi.get(i);
            if (mm != null) out.addAll(mm); else if (result[i] >= 0) out.add(result[i]);
        }
        if (fromMobi && coverHref != null && !textHeavy) {          // the cover lives in the metadata, not in the spine
            Integer ci = byName.get(resolve(base, coverHref));
            if (ci != null && !out.contains(ci)) out.add(0, ci);
        }
        return out;
    }
}
