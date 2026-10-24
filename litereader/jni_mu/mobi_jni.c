/* MOBI/AZW/AZW3 -> EPUB converter (libmobi, LGPL-3.0). Writes an EPUB zip that the MuPDF text path (or the image engine) opens.
 * Based on create_epub() from libmobi's mobitool. */
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "mobi.h"
#define MINIZ_HEADER_FILE_ONLY
#define MINIZ_NO_ZLIB_COMPATIBLE_NAMES
#include "libmobi/miniz.c"

#define EPUB_CONTAINER "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n  <rootfiles>\n    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n  </rootfiles>\n</container>"
#define EPUB_MIMETYPE "application/epub+zip"

static char merr[200] = "";
static void seterr(const char *s) { strncpy(merr, s, sizeof merr - 1); merr[sizeof merr - 1] = 0; }

JNIEXPORT jstring JNICALL Java_com_litereader_NativeMu_mobiError(JNIEnv *e, jclass c) { return (*e)->NewStringUTF(e, merr); }

static mz_uint level_for(int type) {
    switch (type) { case T_HTML: case T_CSS: case T_OPF: case T_NCX: case T_SVG: return (mz_uint)MZ_DEFAULT_COMPRESSION; default: return (mz_uint)MZ_NO_COMPRESSION; }
}

static int add(mz_zip_archive *z, const char *name, const void *data, size_t size, mz_uint lvl) {
    if (!mz_zip_writer_add_mem(z, name, data, size, lvl)) { seterr("Could not write the converted book"); return 0; }
    return 1;
}

/* returns 0 ok, 1 DRM/encrypted, 2 print replica (AZW4), 3 cannot parse, 4 cannot write, 5 out of memory */
JNIEXPORT jint JNICALL Java_com_litereader_NativeMu_mobiToEpub(JNIEnv *e, jclass c, jstring jin, jstring jout) {
    const char *in = (*e)->GetStringUTFChars(e, jin, NULL), *out = (*e)->GetStringUTFChars(e, jout, NULL);
    int rc = 0; merr[0] = 0;
    MOBIData *m = mobi_init();
    MOBIRawml *rawml = NULL;
    mz_zip_archive zip; memset(&zip, 0, sizeof zip);
    int zopen = 0;
    char tmp[1100];
    snprintf(tmp, sizeof tmp, "%s.tmp", out);
    if (!m) { rc = 5; seterr("Out of memory"); goto done; }
    FILE *f = fopen(in, "rb");
    if (!f) { rc = 3; seterr("Cannot open file"); goto done; }
    MOBI_RET r = mobi_load_file(m, f);
    fclose(f);
    if (r != MOBI_SUCCESS) { rc = (r == MOBI_FILE_ENCRYPTED || r == MOBI_DRM_KEYNOTFOUND) ? 1 : 3; seterr(rc == 1 ? "DRM-protected" : "Not a readable MOBI/AZW file"); goto done; }
    if (mobi_is_encrypted(m)) { rc = 1; seterr("DRM-protected"); goto done; }
    if (mobi_is_replica(m)) { rc = 2; seterr("Print replica (AZW4)"); goto done; }
    rawml = mobi_init_rawml(m);
    if (!rawml) { rc = 5; seterr("Out of memory"); goto done; }
    r = mobi_parse_rawml(rawml, m);
    if (r != MOBI_SUCCESS) { rc = (r == MOBI_FILE_ENCRYPTED || r == MOBI_DRM_KEYNOTFOUND) ? 1 : 3; seterr(rc == 1 ? "DRM-protected" : "Could not parse the book"); goto done; }
    if (!mz_zip_writer_init_file(&zip, tmp, 0)) { rc = 4; seterr("Cannot create converted file"); goto done; }
    zopen = 1;
    if (!add(&zip, "mimetype", EPUB_MIMETYPE, sizeof(EPUB_MIMETYPE) - 1, MZ_NO_COMPRESSION) ||
        !add(&zip, "META-INF/container.xml", EPUB_CONTAINER, sizeof(EPUB_CONTAINER) - 1, MZ_DEFAULT_COMPRESSION)) { rc = 4; goto done; }
    char name[128];
    for (MOBIPart *p = rawml->markup; p; p = p->next) {
        MOBIFileMeta fm = mobi_get_filemeta_by_type(p->type);
        snprintf(name, sizeof name, "OEBPS/part%05zu.%s", p->uid, fm.extension);
        if (!add(&zip, name, p->data, p->size, MZ_DEFAULT_COMPRESSION)) { rc = 4; goto done; }
    }
    if (rawml->flow) {
        for (MOBIPart *p = rawml->flow->next; p; p = p->next) {
            MOBIFileMeta fm = mobi_get_filemeta_by_type(p->type);
            snprintf(name, sizeof name, "OEBPS/flow%05zu.%s", p->uid, fm.extension);
            if (!add(&zip, name, p->data, p->size, MZ_DEFAULT_COMPRESSION)) { rc = 4; goto done; }
        }
    }
    for (MOBIPart *p = rawml->resources; p; p = p->next) {
        MOBIFileMeta fm = mobi_get_filemeta_by_type(p->type);
        if (p->size == 0) continue;
        if (fm.type == T_OPF) snprintf(name, sizeof name, "OEBPS/content.opf");
        else snprintf(name, sizeof name, "OEBPS/resource%05zu.%s", p->uid, fm.extension);
        if (!add(&zip, name, p->data, p->size, level_for(fm.type))) { rc = 4; goto done; }
    }
    if (!mz_zip_writer_finalize_archive(&zip)) { rc = 4; seterr("Could not finish converted file"); goto done; }
    mz_zip_writer_end(&zip); zopen = 0;
    if (rename(tmp, out) != 0) { rc = 4; seterr("Could not store converted file"); }
done:
    if (zopen) mz_zip_writer_end(&zip);
    if (rc != 0) remove(tmp);
    if (rawml) mobi_free_rawml(rawml);
    if (m) mobi_free(m);
    (*e)->ReleaseStringUTFChars(e, jin, in); (*e)->ReleaseStringUTFChars(e, jout, out);
    return rc;
}
