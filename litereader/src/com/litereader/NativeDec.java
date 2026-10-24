package com.litereader;

import java.nio.ByteBuffer;

final class NativeDec {
    static { System.loadLibrary("lrdec"); }
    /** malloc'd (not zeroed) direct buffer; untouched pages cost no RAM. */
    static native ByteBuffer alloc(int size);
    static native void free(ByteBuffer b);
    /** returns 0 ok; info = w,h,fmt(0=L8,1=RGB565),rowBytes,thumbW,thumbH */
    static native int decode(ByteBuffer src, int srcLen, int method, ByteBuffer out, int outCap, ByteBuffer thumb, int thumbCap, int[] info);
}
