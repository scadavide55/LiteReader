/* LiteReader native page decoder.
 * Input: zip entry bytes (stored or raw-deflate) in native memory containing a PNG or JPEG.
 * Output: L8 (grey) or RGB565 pixels into caller-owned native memory, plus an optional 1/T box-filtered thumbnail.
 * PNG: 8-bit, non-interlaced (grey, RGB, palette, grey+alpha, RGBA). JPEG: baseline/progressive grey or YCbCr.
 * Nothing is allocated on the Java heap. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <stdio.h>
#include <zlib.h>
#include "jpeglib.h"
#include "jerror.h"
#ifndef HOST_TEST
#include <jni.h>
#endif

#define T 6

typedef struct { const uint8_t *src; size_t len, pos; int method; z_stream z; uint8_t pb[16]; int pbn, pbi; } Zr;

static size_t zr_read(Zr *r, uint8_t *dst, size_t n) {
    size_t got = 0;
    while (r->pbi < r->pbn && got < n) dst[got++] = r->pb[r->pbi++];
    if (got == n) return got;
    dst += got; n -= got;
    if (r->method == 0) {
        size_t k = r->len - r->pos; if (k > n) k = n;
        memcpy(dst, r->src + r->pos, k); r->pos += k; return got + k;
    }
    r->z.next_out = dst; r->z.avail_out = (uInt)n;
    while (r->z.avail_out > 0) {
        int rc = inflate(&r->z, Z_NO_FLUSH);
        if (rc == Z_STREAM_END) break;
        if (rc != Z_OK && rc != Z_BUF_ERROR) break;
        if (rc == Z_BUF_ERROR && r->z.avail_in == 0) break;
    }
    return got + (n - r->z.avail_out);
}
static uint32_t be32(const uint8_t *p) { return ((uint32_t)p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]; }
static int skipn(Zr *r, size_t n) {
    uint8_t b[4096];
    while (n) { size_t k = n > sizeof b ? sizeof b : n; size_t g = zr_read(r, b, k); if (!g) return 0; n -= g; }
    return 1;
}

/* ---- output sink: converts decoded rows (already L8 or RGB565 in orow) into out + thumb ---- */
typedef struct {
    uint8_t *out, *thumb; uint32_t *acc;
    uint32_t w, h, tw, th; int fmt; size_t pxb;
} Sink;

static int sink_init(Sink *s, uint32_t w, uint32_t h, int fmt, uint8_t *out, size_t outCap, uint8_t *thumb, size_t thumbCap) {
    memset(s, 0, sizeof *s);
    s->w = w; s->h = h; s->fmt = fmt; s->pxb = fmt ? 2 : 1;
    if (out && (size_t)w * h * s->pxb > outCap) return -4;
    s->out = out;
    s->tw = w / T; s->th = h / T;
    if (thumb && s->tw > 0 && s->th > 0 && (size_t)s->tw * s->th * s->pxb <= thumbCap) {
        s->thumb = thumb;
        s->acc = (uint32_t *)calloc((size_t)s->tw * 3 + 4, 4);
        if (!s->acc) return -11;
    } else { s->tw = s->th = 0; }
    return 0;
}
static void sink_row(Sink *s, uint32_t y, const uint8_t *orow) {
    uint32_t w = s->w, tw = s->tw, tx, k;
    if (s->out) memcpy(s->out + (size_t)y * w * s->pxb, orow, (size_t)w * s->pxb);
    if (!s->thumb || y >= s->th * T) return;
    uint32_t *acc = s->acc;
    if (s->fmt == 0) {
        for (tx = 0; tx < tw; tx++) { uint32_t sm = 0; const uint8_t *p = orow + tx * T; for (k = 0; k < T; k++) sm += p[k]; acc[tx] += sm; }
    } else {
        const uint16_t *o = (const uint16_t *)orow;
        for (tx = 0; tx < tw; tx++) {
            uint32_t sr = 0, sg = 0, sb = 0; const uint16_t *p = o + tx * T;
            for (k = 0; k < T; k++) { uint32_t v = p[k]; sr += v >> 11; sg += (v >> 5) & 63; sb += v & 31; }
            acc[tx * 3] += sr; acc[tx * 3 + 1] += sg; acc[tx * 3 + 2] += sb;
        }
    }
    if (y % T == T - 1) {
        uint32_t ty = y / T;
        for (tx = 0; tx < tw; tx++) {
            if (s->fmt == 0) { s->thumb[(size_t)ty * tw + tx] = (uint8_t)(acc[tx] / (T * T)); acc[tx] = 0; }
            else {
                uint32_t r = acc[tx * 3] / (T * T), g = acc[tx * 3 + 1] / (T * T), b = acc[tx * 3 + 2] / (T * T);
                ((uint16_t *)s->thumb)[(size_t)ty * tw + tx] = (uint16_t)((r << 11) | (g << 5) | b);
                acc[tx * 3] = acc[tx * 3 + 1] = acc[tx * 3 + 2] = 0;
            }
        }
    }
}
static void sink_done(Sink *s, int *info) {
    info[0] = (int)s->w; info[1] = (int)s->h; info[2] = s->fmt; info[3] = (int)(s->w * s->pxb);
    info[4] = (int)s->tw; info[5] = (int)s->th;
}

/* ---- PNG ---- */
static void unfilter(int f, uint8_t *cur, const uint8_t *prev, int bpp, size_t n) {
    size_t i;
    switch (f) {
    case 1: for (i = bpp; i < n; i++) cur[i] += cur[i - bpp]; break;
    case 2: for (i = 0; i < n; i++) cur[i] += prev[i]; break;
    case 3:
        for (i = 0; i < (size_t)bpp && i < n; i++) cur[i] += prev[i] >> 1;
        for (i = bpp; i < n; i++) cur[i] += (cur[i - bpp] + prev[i]) >> 1;
        break;
    case 4:
        for (i = 0; i < (size_t)bpp && i < n; i++) cur[i] += prev[i];
        for (i = bpp; i < n; i++) {
            int a = cur[i - bpp], b = prev[i], c = prev[i - bpp];
            int p = a + b - c, pa = p > a ? p - a : a - p, pb = p > b ? p - b : b - p, pc = p > c ? p - c : c - p;
            cur[i] += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
        }
        break;
    default: break;
    }
}
typedef struct { Zr *zr; uint32_t remain; uint8_t inbuf[32768]; } Idat;
static size_t idat_feed(Idat *s) {
    uint8_t h[8];
    while (s->remain == 0) {
        if (!skipn(s->zr, 4)) return 0;
        if (zr_read(s->zr, h, 8) != 8) return 0;
        if (memcmp(h + 4, "IDAT", 4)) return 0;
        s->remain = be32(h);
    }
    size_t n = s->remain > sizeof s->inbuf ? sizeof s->inbuf : s->remain;
    size_t g = zr_read(s->zr, s->inbuf, n);
    s->remain -= (uint32_t)g;
    return g;
}

static int decode_png(Zr *zr, uint8_t *out, size_t outCap, uint8_t *thumb, size_t thumbCap, int *info) {
    uint32_t w = 0, h = 0, clen; int depth = 0, ct = 0, il = 0, npal = 0, haveHdr = 0, found = 0, rc = 0;
    uint8_t pal[768], ch[8];
    Idat *id = (Idat *)malloc(sizeof(Idat));
    if (!id) return -11;
    id->zr = zr; id->remain = 0;
    while (zr_read(zr, ch, 8) == 8) {
        clen = be32(ch);
        if (!memcmp(ch + 4, "IHDR", 4)) {
            uint8_t b[13];
            if (clen != 13 || zr_read(zr, b, 13) != 13 || !skipn(zr, 4)) { free(id); return -2; }
            w = be32(b); h = be32(b + 4); depth = b[8]; ct = b[9]; il = b[12]; haveHdr = 1;
        } else if (!memcmp(ch + 4, "PLTE", 4)) {
            if (clen > 768 || zr_read(zr, pal, clen) != clen || !skipn(zr, 4)) { free(id); return -2; }
            npal = clen / 3;
        } else if (!memcmp(ch + 4, "IDAT", 4)) { id->remain = clen; found = 1; break; }
        else if (!skipn(zr, (size_t)clen + 4)) { free(id); return -2; }
    }
    if (!found || !haveHdr) { free(id); return -3; }
    if (w == 0 || h == 0 || w > 8192 || h > 8192 || il > 1 || !(ct == 0 || ct == 2 || ct == 3 || ct == 4 || ct == 6) ||
        !(depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16) || ((ct == 2 || ct == 4 || ct == 6) && depth < 8) || (ct == 3 && depth == 16)) { free(id); return -12; }
    uint8_t lut8[256]; uint16_t lut16[256]; int fmt, i;
    memset(lut8, 0, sizeof lut8); memset(lut16, 0, sizeof lut16);
    if (ct == 3) {
        int allgray = 1;
        for (i = 0; i < npal; i++) {
            int r = pal[i * 3], g = pal[i * 3 + 1], b = pal[i * 3 + 2];
            if (r != g || g != b) allgray = 0;
            lut8[i] = (uint8_t)g;
            lut16[i] = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
        }
        fmt = allgray ? 0 : 1;
    } else fmt = (ct == 0 || ct == 4) ? 0 : 1;
    int chans = ct == 0 ? 1 : ct == 2 ? 3 : ct == 3 ? 1 : ct == 4 ? 2 : 4;
    int bitsPx = chans * depth, bppIn = bitsPx >= 8 ? bitsPx / 8 : 1;
    Sink sk;
    uint8_t *tmpOut = NULL, *dst = out;
    if (il) {
        /* interlaced: decode into a full buffer first, thumbnail afterwards */
        size_t need = (size_t)w * h * (fmt ? 2 : 1);
        if (!out) { tmpOut = (uint8_t *)malloc(need); if (!tmpOut) { free(id); return -11; } dst = tmpOut; }
        else if (need > outCap) { free(id); return -4; }
        rc = sink_init(&sk, w, h, fmt, NULL, 0, thumb, thumbCap);
    } else rc = sink_init(&sk, w, h, fmt, out, outCap, thumb, thumbCap);
    if (rc) { free(id); free(tmpOut); return rc; }
    size_t maxRowBytes = ((size_t)w * bitsPx + 7) / 8, lineSz = (maxRowBytes + 8) & ~(size_t)3;
    uint8_t *mem = (uint8_t *)malloc(lineSz * 2), *orow = (uint8_t *)malloc((size_t)w * 2 + 8), *smp = (uint8_t *)malloc((size_t)w * 4 + 8);
    z_stream zs; memset(&zs, 0, sizeof zs);
    if (!mem || !orow || !smp || inflateInit(&zs) != Z_OK) { free(mem); free(orow); free(smp); free(id); free(sk.acc); free(tmpOut); return -11; }
    static const int PX[7] = {0, 4, 0, 2, 0, 1, 0}, PY[7] = {0, 0, 4, 0, 2, 0, 1}, DX[7] = {8, 8, 4, 4, 2, 2, 1}, DY[7] = {8, 8, 8, 4, 4, 2, 2};
    int npass = il ? 7 : 1, pass;
    for (pass = 0; pass < npass && rc == 0; pass++) {
        uint32_t xs = il ? PX[pass] : 0, ys = il ? PY[pass] : 0, dx = il ? DX[pass] : 1, dy = il ? DY[pass] : 1;
        if (xs >= w || ys >= h) continue;
        uint32_t pw = (w - xs + dx - 1) / dx, ph = (h - ys + dy - 1) / dy;
        size_t rowIn = ((size_t)pw * bitsPx + 7) / 8;
        uint8_t *cur = mem, *prev = mem + lineSz;
        memset(mem, 0, lineSz * 2);
        uint32_t r;
        for (r = 0; r < ph; r++) {
            zs.next_out = cur; zs.avail_out = (uInt)(rowIn + 1);
            while (zs.avail_out) {
                if (zs.avail_in == 0) {
                    size_t n = idat_feed(id);
                    if (!n) { rc = -6; break; }
                    zs.next_in = id->inbuf; zs.avail_in = (uInt)n;
                }
                int r2 = inflate(&zs, Z_NO_FLUSH);
                if (r2 == Z_STREAM_END && zs.avail_out) { rc = -7; break; }
                if (r2 < 0 && r2 != Z_BUF_ERROR) { rc = -8; break; }
            }
            if (rc) break;
            unfilter(cur[0], cur + 1, prev + 1, bppIn, rowIn);
            /* expand to one byte per sample */
            const uint8_t *rw = cur + 1; uint32_t x; const uint8_t *s;
            if (depth == 8) s = rw;
            else if (depth == 16) { for (x = 0; x < pw * chans; x++) smp[x] = rw[x * 2]; s = smp; }
            else {
                int mask = (1 << depth) - 1, scale = ct == 0 ? 255 / mask : 1;
                for (x = 0; x < pw; x++) {
                    int bit = x * depth, v = (rw[bit >> 3] >> (8 - depth - (bit & 7))) & mask;
                    smp[x] = (uint8_t)(v * scale);
                }
                s = smp;
            }
            if (ct == 0) memcpy(orow, s, pw);
            else if (ct == 3) {
                if (fmt == 0) for (x = 0; x < pw; x++) orow[x] = lut8[s[x]];
                else { uint16_t *o = (uint16_t *)orow; for (x = 0; x < pw; x++) o[x] = lut16[s[x]]; }
            } else if (ct == 4) for (x = 0; x < pw; x++) orow[x] = s[x * 2];
            else if (ct == 2) {
                uint16_t *o = (uint16_t *)orow;
                for (x = 0; x < pw; x++, s += 3) o[x] = (uint16_t)(((s[0] >> 3) << 11) | ((s[1] >> 2) << 5) | (s[2] >> 3));
            } else {
                uint16_t *o = (uint16_t *)orow;
                for (x = 0; x < pw; x++, s += 4) {
                    int a = s[3], rr = s[0], g = s[1], b = s[2];
                    if (a != 255) { int ia = 255 - a; rr = (rr * a + 255 * ia) / 255; g = (g * a + 255 * ia) / 255; b = (b * a + 255 * ia) / 255; }
                    o[x] = (uint16_t)(((rr >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
                }
            }
            if (!il) sink_row(&sk, r, orow);
            else {
                uint32_t y = ys + r * dy; size_t pxb = fmt ? 2 : 1;
                uint8_t *d = dst + ((size_t)y * w + xs) * pxb;
                if (pxb == 1) for (x = 0; x < pw; x++) d[x * dx] = orow[x];
                else for (x = 0; x < pw; x++) { d[x * dx * 2] = orow[x * 2]; d[x * dx * 2 + 1] = orow[x * 2 + 1]; }
            }
            { uint8_t *t = cur; cur = prev; prev = t; }
        }
    }
    inflateEnd(&zs);
    if (rc == 0 && il && sk.thumb) {
        uint32_t y; size_t pxb = fmt ? 2 : 1;
        for (y = 0; y < h; y++) sink_row(&sk, y, dst + (size_t)y * w * pxb);
    }
    free(mem); free(orow); free(smp); free(id); free(sk.acc); free(tmpOut);
    if (rc == 0) sink_done(&sk, info);
    return rc;
}

/* ---- JPEG ---- */
typedef struct { struct jpeg_source_mgr pub; Zr *zr; uint8_t buf[32768]; } JSrc;
typedef struct { struct jpeg_error_mgr pub; jmp_buf jb; } JErr;
static void js_init(j_decompress_ptr c) { (void)c; }
static boolean js_fill(j_decompress_ptr c) {
    JSrc *s = (JSrc *)c->src;
    size_t n = zr_read(s->zr, s->buf, sizeof s->buf);
    if (n == 0) { s->buf[0] = 0xFF; s->buf[1] = JPEG_EOI; n = 2; }
    s->pub.next_input_byte = s->buf; s->pub.bytes_in_buffer = n;
    return TRUE;
}
static void js_skip(j_decompress_ptr c, long n) {
    JSrc *s = (JSrc *)c->src;
    while (n > (long)s->pub.bytes_in_buffer) { n -= (long)s->pub.bytes_in_buffer; js_fill(c); }
    s->pub.next_input_byte += n; s->pub.bytes_in_buffer -= n;
}
static void js_term(j_decompress_ptr c) { (void)c; }
static void jerr_exit(j_common_ptr c) { longjmp(((JErr *)c->err)->jb, 1); }
static void jerr_msg(j_common_ptr c) { (void)c; }

static int decode_jpeg(Zr *zr, uint8_t *out, size_t outCap, uint8_t *thumb, size_t thumbCap, int *info) {
    struct jpeg_decompress_struct ci; JErr je; JSrc *js = (JSrc *)malloc(sizeof(JSrc));
    uint8_t *rowbuf = NULL, *orow = NULL; Sink sk; int sinkInit = 0, rc = 0;
    if (!js) return -11;
    memset(&sk, 0, sizeof sk);
    ci.err = jpeg_std_error(&je.pub); je.pub.error_exit = jerr_exit; je.pub.output_message = jerr_msg;
    if (setjmp(je.jb)) { jpeg_destroy_decompress(&ci); free(js); free(rowbuf); free(orow); free(sk.acc); return -20; }
    jpeg_create_decompress(&ci);
    js->zr = zr; js->pub.init_source = js_init; js->pub.fill_input_buffer = js_fill; js->pub.skip_input_data = js_skip;
    js->pub.resync_to_restart = jpeg_resync_to_restart; js->pub.term_source = js_term;
    js->pub.bytes_in_buffer = 0; js->pub.next_input_byte = NULL;
    ci.src = &js->pub;
    jpeg_read_header(&ci, TRUE);
    int gray = (ci.num_components == 1);
    if (ci.num_components != 1 && ci.num_components != 3) { jpeg_destroy_decompress(&ci); free(js); return -21; }
    ci.out_color_space = gray ? JCS_GRAYSCALE : JCS_RGB;
    ci.dct_method = JDCT_IFAST;
    ci.do_fancy_upsampling = FALSE;
    jpeg_start_decompress(&ci);
    uint32_t w = ci.output_width, h = ci.output_height;
    if (w == 0 || h == 0 || w > 8192 || h > 8192) { jpeg_destroy_decompress(&ci); free(js); return -12; }
    rc = sink_init(&sk, w, h, gray ? 0 : 1, out, outCap, thumb, thumbCap);
    if (rc) { jpeg_destroy_decompress(&ci); free(js); free(sk.acc); return rc; }
    sinkInit = 1;
    rowbuf = (uint8_t *)malloc((size_t)w * ci.output_components + 8);
    orow = (uint8_t *)malloc((size_t)w * 2 + 8);
    if (!rowbuf || !orow) { jpeg_destroy_decompress(&ci); free(js); free(rowbuf); free(orow); free(sk.acc); return -11; }
    while (ci.output_scanline < h) {
        uint32_t y = ci.output_scanline;
        JSAMPROW rp = rowbuf;
        jpeg_read_scanlines(&ci, &rp, 1);
        if (gray) memcpy(orow, rowbuf, w);
        else {
            uint16_t *o = (uint16_t *)orow; const uint8_t *s = rowbuf; uint32_t x;
            for (x = 0; x < w; x++, s += 3) o[x] = (uint16_t)(((s[0] >> 3) << 11) | ((s[1] >> 2) << 5) | (s[2] >> 3));
        }
        sink_row(&sk, y, orow);
    }
    (void)sinkInit;
    jpeg_abort_decompress(&ci);
    jpeg_destroy_decompress(&ci);
    free(js); free(rowbuf); free(orow); free(sk.acc);
    sink_done(&sk, info);
    return 0;
}

/* returns 0 ok, <0 error. info[0..5] = w,h,fmt(0=L8,1=RGB565),rowBytes,thumbW,thumbH (thumbW=0: no thumb) */
int lr_decode(const uint8_t *src, size_t len, int method, uint8_t *out, size_t outCap, uint8_t *thumb, size_t thumbCap, int *info) {
    Zr zr; memset(&zr, 0, sizeof zr);
    zr.src = src; zr.len = len; zr.method = method;
    int zInit = 0, rc;
    if (method == 8) {
        if (inflateInit2(&zr.z, -15) != Z_OK) return -10;
        zInit = 1; zr.z.next_in = (Bytef *)src; zr.z.avail_in = (uInt)len;
    } else if (method != 0) return -9;
    uint8_t sig[8];
    size_t n = zr_read(&zr, sig, 8);
    if (n == 8 && !memcmp(sig, "\x89PNG\r\n\x1a\n", 8)) rc = decode_png(&zr, out, outCap, thumb, thumbCap, info);
    else if (n >= 3 && sig[0] == 0xFF && sig[1] == 0xD8) {
        memcpy(zr.pb, sig, n); zr.pbn = (int)n; zr.pbi = 0;
        rc = decode_jpeg(&zr, out, outCap, thumb, thumbCap, info);
    } else rc = -30;
    if (zInit) inflateEnd(&zr.z);
    return rc;
}

#ifndef HOST_TEST
JNIEXPORT jobject JNICALL Java_com_litereader_NativeDec_alloc(JNIEnv *e, jclass c, jint size) {
    void *p = malloc((size_t)size);
    if (!p) return NULL;
    return (*e)->NewDirectByteBuffer(e, p, size);
}
JNIEXPORT void JNICALL Java_com_litereader_NativeDec_free(JNIEnv *e, jclass c, jobject b) {
    free((*e)->GetDirectBufferAddress(e, b));
}
JNIEXPORT jint JNICALL Java_com_litereader_NativeDec_decode(JNIEnv *e, jclass c, jobject src, jint srcLen, jint method,
        jobject out, jint outCap, jobject thumb, jint thumbCap, jintArray infoArr) {
    uint8_t *s = (uint8_t *)(*e)->GetDirectBufferAddress(e, src);
    uint8_t *o = out ? (uint8_t *)(*e)->GetDirectBufferAddress(e, out) : NULL;
    uint8_t *t = thumb ? (uint8_t *)(*e)->GetDirectBufferAddress(e, thumb) : NULL;
    int info[8] = {0};
    int rc = lr_decode(s, (size_t)srcLen, method, o, (size_t)outCap, t, (size_t)thumbCap, info);
    if (rc == 0) (*e)->SetIntArrayRegion(e, infoArr, 0, 6, info);
    return rc;
}
#endif
