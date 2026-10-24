/* LiteReader MuPDF page renderer (PDF, text EPUB, TXT). Separate lazily-loaded library. AGPL-3.0 (links MuPDF).
 * Pages are rendered to RGB565 into caller-owned native memory, optionally with a 1/6-size thumbnail.
 * One document, one context, one mutex: MuPDF contexts are not thread-safe. */
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <mupdf/fitz.h>

#define T 6
#define PIX_BUDGET 2800000.0f

typedef struct { fz_context *ctx; fz_document *doc; int pages; pthread_mutex_t lock; } Mu;
static char lasterr[300] = "";
static fz_font *g_fallback = NULL;

static fz_font *sys_font(fz_context *ctx) {
    if (!g_fallback) {
        fz_try(ctx) g_fallback = fz_new_font_from_file(ctx, NULL, "/system/fonts/DroidSansFallback.ttf", 0, 0);
        fz_catch(ctx) g_fallback = NULL;
    }
    return g_fallback ? fz_keep_font(ctx, g_fallback) : NULL;
}
static fz_font *load_font(fz_context *ctx, const char *name, int bold, int italic, int exact) { return NULL; }
static fz_font *load_cjk(fz_context *ctx, const char *name, int ordering, int serif) { return sys_font(ctx); }
static fz_font *load_fallback(fz_context *ctx, int script, int language, int serif, int bold, int italic) { return sys_font(ctx); }

static void seterr(const char *m) { strncpy(lasterr, m ? m : "", sizeof lasterr - 1); lasterr[sizeof lasterr - 1] = 0; }

JNIEXPORT jlong JNICALL Java_com_litereader_NativeMu_open(JNIEnv *e, jclass c, jstring jpath, jint lw, jint lh, jfloat em) {
    const char *path = (*e)->GetStringUTFChars(e, jpath, NULL);
    Mu *m = (Mu *)calloc(1, sizeof(Mu));
    lasterr[0] = 0;
    m->ctx = fz_new_context(NULL, NULL, 24 << 20);
    if (!m->ctx) { (*e)->ReleaseStringUTFChars(e, jpath, path); free(m); seterr("Out of memory"); return 0; }
    pthread_mutex_init(&m->lock, NULL);
    fz_context *ctx = m->ctx;
    int ok = 0;
    fz_try(ctx) {
        fz_register_document_handlers(ctx);
        fz_install_load_system_font_funcs(ctx, load_font, load_cjk, load_fallback);
        m->doc = fz_open_document(ctx, path);
        if (fz_needs_password(ctx, m->doc) && !fz_authenticate_password(ctx, m->doc, ""))
            fz_throw(ctx, FZ_ERROR_ARGUMENT, "This document is password protected");
        if (fz_is_document_reflowable(ctx, m->doc)) fz_layout_document(ctx, m->doc, (float)lw, (float)lh, em);
        m->pages = fz_count_pages(ctx, m->doc);
        if (m->pages <= 0) fz_throw(ctx, FZ_ERROR_ARGUMENT, "Document has no pages");
        ok = 1;
    }
    fz_catch(ctx) { seterr(fz_caught_message(ctx)); }
    (*e)->ReleaseStringUTFChars(e, jpath, path);
    if (!ok) { if (m->doc) fz_drop_document(ctx, m->doc); fz_drop_context(ctx); free(m); return 0; }
    return (jlong)(intptr_t)m;
}

JNIEXPORT jint JNICALL Java_com_litereader_NativeMu_pageCount(JNIEnv *e, jclass c, jlong h) { return h ? ((Mu *)(intptr_t)h)->pages : 0; }

JNIEXPORT void JNICALL Java_com_litereader_NativeMu_close(JNIEnv *e, jclass c, jlong h) {
    Mu *m = (Mu *)(intptr_t)h;
    if (!m) return;
    pthread_mutex_lock(&m->lock);
    fz_drop_document(m->ctx, m->doc);
    fz_drop_context(m->ctx);
    pthread_mutex_unlock(&m->lock);
    free(m);
}

JNIEXPORT jstring JNICALL Java_com_litereader_NativeMu_lastError(JNIEnv *e, jclass c) { return (*e)->NewStringUTF(e, lasterr); }

static inline uint16_t pack565(int r, int g, int b) { return (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)); }

/* returns 0 ok; info = w,h,fmt(1),rowBytes,thumbW,thumbH */
JNIEXPORT jint JNICALL Java_com_litereader_NativeMu_render(JNIEnv *e, jclass c, jlong h, jint page, jobject outBuf, jint outCap,
        jobject thumbBuf, jint thumbCap, jintArray infoArr) {
    Mu *m = (Mu *)(intptr_t)h;
    if (!m || page < 0 || page >= m->pages) return -1;
    uint8_t *out = outBuf ? (uint8_t *)(*e)->GetDirectBufferAddress(e, outBuf) : NULL;
    uint8_t *th = thumbBuf ? (uint8_t *)(*e)->GetDirectBufferAddress(e, thumbBuf) : NULL;
    int info[8] = {0}, rc = 0;
    pthread_mutex_lock(&m->lock);
    fz_context *ctx = m->ctx;
    fz_page *pg = NULL; fz_pixmap *pix = NULL; fz_device *dev = NULL;
    fz_var(pg); fz_var(pix); fz_var(dev);
    fz_try(ctx) {
        pg = fz_load_page(ctx, m->doc, page);
        fz_rect b = fz_bound_page(ctx, pg);
        float pw = b.x1 - b.x0, ph = b.y1 - b.y0;
        if (pw < 1 || ph < 1) fz_throw(ctx, FZ_ERROR_ARGUMENT, "empty page");
        float budget = PIX_BUDGET;
        if (outCap > 0 && outCap / 2.0f < budget) budget = outCap / 2.0f;
        float s = sqrtf(budget / (pw * ph));
        int W = (int)(pw * s), H = (int)(ph * s);
        if (W < 2) W = 2; if (H < 2) H = 2;
        int tw = W / T, tH = H / T;
        int thumbOnly = (out == NULL);
        float rs = thumbOnly ? s / 3.0f : s;         /* thumbnail-only: render at 1/3 size, then 2x2 box */
        int rw = thumbOnly ? (tw * 2) : W, rh = thumbOnly ? (tH * 2) : H;
        if (rw < 2) rw = 2; if (rh < 2) rh = 2;
        fz_matrix ctm = fz_scale((float)rw / pw, (float)rh / ph);
        (void)rs;
        pix = fz_new_pixmap(ctx, fz_device_rgb(ctx), rw, rh, NULL, 0);
        fz_clear_pixmap_with_value(ctx, pix, 255);
        dev = fz_new_draw_device(ctx, ctm, pix);
        fz_run_page(ctx, pg, dev, fz_identity, NULL);
        fz_close_device(ctx, dev);
        fz_drop_device(ctx, dev); dev = NULL;
        int n = fz_pixmap_components(ctx, pix), stride = fz_pixmap_stride(ctx, pix);
        const uint8_t *smp = fz_pixmap_samples(ctx, pix);
        if (thumbOnly) {
            if (!th || (size_t)tw * tH * 2 > (size_t)thumbCap) fz_throw(ctx, FZ_ERROR_ARGUMENT, "thumb buffer");
            for (int y = 0; y < tH; y++) {
                const uint8_t *r0 = smp + (size_t)(2 * y) * stride, *r1 = r0 + stride;
                for (int x = 0; x < tw; x++) {
                    const uint8_t *a = r0 + (size_t)(2 * x) * n, *bb = r1 + (size_t)(2 * x) * n;
                    int R = (a[0] + a[n] + bb[0] + bb[n]) >> 2, G = (a[1] + a[n + 1] + bb[1] + bb[n + 1]) >> 2, B = (a[2] + a[n + 2] + bb[2] + bb[n + 2]) >> 2;
                    ((uint16_t *)th)[(size_t)y * tw + x] = pack565(R, G, B);
                }
            }
            info[0] = W; info[1] = H; info[2] = 1; info[3] = W * 2; info[4] = tw; info[5] = tH;
        } else {
            if ((size_t)W * H * 2 > (size_t)outCap) fz_throw(ctx, FZ_ERROR_ARGUMENT, "page buffer");
            uint32_t *acc = NULL;
            int doTh = th && tw > 0 && tH > 0 && (size_t)tw * tH * 2 <= (size_t)thumbCap;
            if (doTh) acc = (uint32_t *)calloc((size_t)tw * 3 + 4, 4);
            for (int y = 0; y < H; y++) {
                const uint8_t *s0 = smp + (size_t)y * stride;
                uint16_t *o = (uint16_t *)(out + (size_t)y * W * 2);
                for (int x = 0; x < W; x++, s0 += n) o[x] = pack565(s0[0], s0[1], s0[2]);
                if (doTh && y < tH * T) {
                    for (int tx = 0; tx < tw; tx++) {
                        uint32_t sr = 0, sg = 0, sb = 0;
                        for (int k = 0; k < T; k++) { uint32_t v = o[tx * T + k]; sr += v >> 11; sg += (v >> 5) & 63; sb += v & 31; }
                        acc[tx * 3] += sr; acc[tx * 3 + 1] += sg; acc[tx * 3 + 2] += sb;
                    }
                    if (y % T == T - 1) {
                        int ty = y / T;
                        for (int tx = 0; tx < tw; tx++) {
                            uint32_t r = acc[tx * 3] / (T * T), g = acc[tx * 3 + 1] / (T * T), bl = acc[tx * 3 + 2] / (T * T);
                            ((uint16_t *)th)[(size_t)ty * tw + tx] = (uint16_t)((r << 11) | (g << 5) | bl);
                            acc[tx * 3] = acc[tx * 3 + 1] = acc[tx * 3 + 2] = 0;
                        }
                    }
                }
            }
            free(acc);
            info[0] = W; info[1] = H; info[2] = 1; info[3] = W * 2; info[4] = doTh ? tw : 0; info[5] = doTh ? tH : 0;
        }
    }
    fz_always(ctx) {
        if (dev) fz_drop_device(ctx, dev);
        fz_drop_pixmap(ctx, pix);
        fz_drop_page(ctx, pg);
    }
    fz_catch(ctx) { seterr(fz_caught_message(ctx)); rc = -2; }
    pthread_mutex_unlock(&m->lock);
    if (rc == 0) (*e)->SetIntArrayRegion(e, infoArr, 0, 6, info);
    return rc;
}
