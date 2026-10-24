/* usage: mprobe file.pdf width height  -> fit page into WxH, render RGB, print VmRSS/VmHWM before/after */
#include <mupdf/fitz.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
static void vm(const char *tag) {
    char line[256]; long rss = -1, hwm = -1; FILE *f = fopen("/proc/self/status", "r");
    while (f && fgets(line, sizeof line, f)) {
        if (!strncmp(line, "VmRSS:", 6)) rss = atol(line + 6);
        if (!strncmp(line, "VmHWM:", 6)) hwm = atol(line + 6);
    }
    if (f) fclose(f);
    printf("%-22s VmRSS=%ld kB VmHWM(peak)=%ld kB\n", tag, rss, hwm);
}
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000.0 + t.tv_nsec / 1e6; }
int main(int argc, char **argv) {
    if (argc < 4) return 2;
    int W = atoi(argv[2]), H = atoi(argv[3]);
    vm("start");
    fz_context *ctx = fz_new_context(NULL, NULL, 32 << 20);
    fz_try(ctx) {
        fz_register_document_handlers(ctx);
        vm("after context");
        fz_document *d = fz_open_document(ctx, argv[1]);
        int n = fz_count_pages(ctx, d);
        vm("after open");
        fz_page *pg = fz_load_page(ctx, d, 0);
        fz_rect b = fz_bound_page(ctx, pg);
        float sx = W / (b.x1 - b.x0), sy = H / (b.y1 - b.y0), s = sx < sy ? sx : sy;
        fz_matrix m = fz_scale(s, s);
        double t0 = now();
        fz_pixmap *pix = fz_new_pixmap_from_page(ctx, pg, m, fz_device_rgb(ctx), 0);
        double t1 = now();
        printf("pages=%d page=%.0fx%.0fpt scale=%.3f pixmap=%dx%d render=%.0f ms\n", n, b.x1 - b.x0, b.y1 - b.y0, s, fz_pixmap_width(ctx, pix), fz_pixmap_height(ctx, pix), t1 - t0);
        vm("after render");
        fz_drop_pixmap(ctx, pix);
        fz_drop_page(ctx, pg);
        vm("after drop pixmap");
        fz_drop_document(ctx, d);
    } fz_catch(ctx) { printf("error: %s\n", fz_caught_message(ctx)); }
    fz_drop_context(ctx);
    vm("after context drop");
    return 0;
}
