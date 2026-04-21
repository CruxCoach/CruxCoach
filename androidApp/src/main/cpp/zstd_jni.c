#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "zstd/zstd.h"

/*
 * JNI wrapper for zstd streaming decompression on Android.
 * Uses ZSTD_decompressStream so content size in frame header is not required.
 */

#define CHUNK_SIZE (64 * 1024)

JNIEXPORT void JNICALL
Java_com_cruxcoach_android_util_ZstdNative_decompressFileNative(
        JNIEnv *env, jclass cls, jstring jInputPath, jstring jOutputPath,
        jlong maxOutputBytes) {
    const char *inputPath = (*env)->GetStringUTFChars(env, jInputPath, NULL);
    const char *outputPath = (*env)->GetStringUTFChars(env, jOutputPath, NULL);
    if (!inputPath || !outputPath) {
        if (inputPath) (*env)->ReleaseStringUTFChars(env, jInputPath, inputPath);
        if (outputPath) (*env)->ReleaseStringUTFChars(env, jOutputPath, outputPath);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                         "Failed to get file path strings");
        return;
    }

    FILE *fin = fopen(inputPath, "rb");
    if (!fin) {
        (*env)->ReleaseStringUTFChars(env, jInputPath, inputPath);
        (*env)->ReleaseStringUTFChars(env, jOutputPath, outputPath);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                         "Cannot open input file");
        return;
    }

    FILE *fout = fopen(outputPath, "wb");
    if (!fout) {
        fclose(fin);
        (*env)->ReleaseStringUTFChars(env, jInputPath, inputPath);
        (*env)->ReleaseStringUTFChars(env, jOutputPath, outputPath);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                         "Cannot open output file");
        return;
    }

    ZSTD_DCtx *dctx = ZSTD_createDCtx();
    if (!dctx) {
        fclose(fin);
        fclose(fout);
        (*env)->ReleaseStringUTFChars(env, jInputPath, inputPath);
        (*env)->ReleaseStringUTFChars(env, jOutputPath, outputPath);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                         "Failed to create ZSTD decompression context");
        return;
    }

    void *inBuf = malloc(ZSTD_DStreamInSize());
    void *outBuf = malloc(ZSTD_DStreamOutSize());
    if (!inBuf || !outBuf) {
        free(inBuf);
        free(outBuf);
        ZSTD_freeDCtx(dctx);
        fclose(fin);
        fclose(fout);
        (*env)->ReleaseStringUTFChars(env, jInputPath, inputPath);
        (*env)->ReleaseStringUTFChars(env, jOutputPath, outputPath);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"),
                         "Failed to allocate zstd buffers");
        return;
    }

    size_t const outBufSize = ZSTD_DStreamOutSize();
    int error = 0;
    jlong totalOut = 0;

    while (1) {
        size_t read = fread(inBuf, 1, ZSTD_DStreamInSize(), fin);
        if (read == 0) break;

        ZSTD_inBuffer input = { inBuf, read, 0 };
        while (input.pos < input.size) {
            ZSTD_outBuffer output = { outBuf, outBufSize, 0 };
            size_t ret = ZSTD_decompressStream(dctx, &output, &input);
            if (ZSTD_isError(ret)) {
                const char *msg = ZSTD_getErrorName(ret);
                (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), msg);
                error = 1;
                break;
            }
            if (output.pos > 0) {
                /* Bomb guard: refuse to write past the caller-supplied cap.
                 * We check after decompress and before fwrite so the on-disk
                 * file never grows beyond maxOutputBytes. */
                totalOut += (jlong) output.pos;
                if (totalOut > maxOutputBytes) {
                    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                                     "Decompressed output exceeds maximum allowed size");
                    error = 1;
                    break;
                }
                fwrite(outBuf, 1, output.pos, fout);
            }
        }
        if (error) break;
    }

    free(inBuf);
    free(outBuf);
    ZSTD_freeDCtx(dctx);
    fclose(fin);
    fclose(fout);
    (*env)->ReleaseStringUTFChars(env, jInputPath, inputPath);
    (*env)->ReleaseStringUTFChars(env, jOutputPath, outputPath);
}
