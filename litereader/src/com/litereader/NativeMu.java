package com.litereader;

import java.nio.ByteBuffer;

/** MuPDF (PDF, text EPUB, TXT) and libmobi (MOBI/AZW/AZW3 -> EPUB). Loaded lazily; the image engine never touches this. */
final class NativeMu {
    private static boolean loaded, failed;

    static synchronized boolean load() {
        if (loaded) return true;
        if (failed) return false;
        try { System.loadLibrary("lrmu"); loaded = true; } catch (Throwable t) { failed = true; }
        return loaded;
    }

    static native long open(String path, int layoutW, int layoutH, float em);
    static native int pageCount(long h);
    static native int render(long h, int page, ByteBuffer out, int outCap, ByteBuffer thumb, int thumbCap, int[] info);
    static native void close(long h);
    static native String lastError();
    /** 0 ok, 1 DRM, 2 print replica, 3 unreadable, 4 write error, 5 out of memory */
    static native int mobiToEpub(String in, String out);
    static native String mobiError();
}
