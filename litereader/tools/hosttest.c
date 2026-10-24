#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
int lr_decode(const uint8_t *src, size_t len, int method, uint8_t *out, size_t outCap, uint8_t *thumb, size_t thumbCap, int *info);
int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc(n); fread(b, 1, n, f); fclose(f);
    size_t cap = 8 << 20; uint8_t *out = malloc(cap), *th = malloc(1 << 20); int info[8] = {0};
    clock_t t0 = clock();
    int rc = lr_decode(b, n, 0, out, cap, th, 1 << 20, info);
    double ms = (clock() - t0) * 1000.0 / CLOCKS_PER_SEC;
    printf("rc=%d w=%d h=%d fmt=%d rb=%d tw=%d th=%d ms=%.1f\n", rc, info[0], info[1], info[2], info[3], info[4], info[5], ms);
    FILE *o = fopen(argv[2], "wb"); fwrite(out, 1, (size_t)info[3] * info[1], o); fclose(o);
    FILE *t = fopen(argv[3], "wb"); fwrite(th, 1, (size_t)info[4] * info[5] * (info[2] ? 2 : 1), t); fclose(t);
    return rc;
}
