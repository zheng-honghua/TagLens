#include <jni.h>
#include <android/bitmap.h>
#include <arm_neon.h>
#include <stdint.h>
#include <stdlib.h>
#include "apriltag.h"
#include "board_recovery.h"
#include "tag36h11.h"
#include "tag25h9.h"
#include "tag16h5.h"
#include "tagStandard41h12.h"
#include "tagStandard52h13.h"
#include "tagCircle21h7.h"
#include "tagCircle49h12.h"
#include "tagCustom48h12.h"

typedef apriltag_family_t *(*create_family)(void);
typedef void (*destroy_family)(apriltag_family_t *);

static const create_family create[] = {
    tag36h11_create, tag25h9_create, tag16h5_create, tagStandard41h12_create,
    tagStandard52h13_create, tagCircle21h7_create, tagCircle49h12_create, tagCustom48h12_create
};
static const destroy_family destroy[] = {
    tag36h11_destroy, tag25h9_destroy, tag16h5_destroy, tagStandard41h12_destroy,
    tagStandard52h13_destroy, tagCircle21h7_destroy, tagCircle49h12_destroy, tagCustom48h12_destroy
};
static const int corrected_bits[] = {1, 0, 0, 0, 0, 0, 0, 0};

typedef struct {
    apriltag_detector_t *detector;
    apriltag_family_t *family;
    int family_index;
    uint8_t *gray;
    size_t capacity;
} engine_t;

static void fail(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}

JNIEXPORT jlong JNICALL Java_com_taglens_scanner_NativeEngine_create(JNIEnv *env, jclass clazz) {
    engine_t *engine = calloc(1, sizeof(*engine));
    if (!engine) {
        fail(env, "Cannot allocate detector");
        return 0;
    }
    return (jlong)(intptr_t)engine;
}

static void reset(engine_t *engine) {
    if (engine->detector) apriltag_detector_destroy(engine->detector);
    if (engine->family) destroy[engine->family_index](engine->family);
    engine->detector = NULL;
    engine->family = NULL;
}

JNIEXPORT void JNICALL Java_com_taglens_scanner_NativeEngine_destroy(JNIEnv *env, jclass clazz, jlong handle) {
    engine_t *engine = (engine_t *)(intptr_t)handle;
    if (!engine) return;
    reset(engine);
    free(engine->gray);
    free(engine);
}

static jfloatArray detect(JNIEnv *env, engine_t *engine, uint8_t *pixels,
                          int width, int height, int family_index, int threads, float decimate, bool board) {
    if (!engine || !pixels || width < 16 || height < 16 || family_index < 0 || family_index >= 8) {
        return (*env)->NewFloatArray(env, 0);
    }
    if (!engine->detector || engine->family_index != family_index) {
        reset(engine);
        engine->family_index = family_index;
        engine->family = create[family_index]();
        engine->detector = apriltag_detector_create();
        apriltag_detector_add_family_bits(engine->detector, engine->family, corrected_bits[family_index]);
    }
    engine->detector->nthreads = threads;
    engine->detector->quad_decimate = decimate;
    engine->detector->refine_edges = true;
    image_u8_t image = {.width=width, .height=height, .stride=width, .buf=pixels};
    zarray_t *found = taglens_detect(engine->detector, &image, board && family_index == 0);
    int count = zarray_size(found);
    enum { FIELDS = 13 };
    jfloatArray array = (*env)->NewFloatArray(env, count * FIELDS);
    if (array && count) {
        float *values = (*env)->GetFloatArrayElements(env, array, NULL);
        if (values) {
            for (int i = 0; i < count; ++i) {
                apriltag_detection_t *detection;
                zarray_get(found, i, &detection);
                float *v = values + i * FIELDS;
                v[0] = detection->id;
                v[1] = detection->c[0];
                v[2] = detection->c[1];
                for (int c = 0; c < 4; ++c) {
                    v[3+c*2] = detection->p[c][0];
                    v[4+c*2] = detection->p[c][1];
                }
                v[11] = detection->decision_margin;
                v[12] = detection->hamming;
            }
            (*env)->ReleaseFloatArrayElements(env, array, values, 0);
        }
    }
    apriltag_detections_destroy(found);
    return array;
}

JNIEXPORT jfloatArray JNICALL Java_com_taglens_scanner_NativeEngine_detectY(JNIEnv *env, jclass clazz,
        jlong handle, jobject input, jint width, jint height, jint family, jint threads, jfloat decimate, jboolean board) {
    uint8_t *pixels = (*env)->GetDirectBufferAddress(env, input);
    if ((*env)->GetDirectBufferCapacity(env, input) < (jlong)width * height) {
        fail(env, "Invalid Y plane");
        return NULL;
    }
    return detect(env, (engine_t *)(intptr_t)handle, pixels, width, height, family, threads, decimate, board);
}

JNIEXPORT jfloatArray JNICALL Java_com_taglens_scanner_NativeEngine_detectBitmap(JNIEnv *env, jclass clazz,
        jlong handle, jobject bitmap, jint family, jint threads, jfloat decimate, jboolean board) {
    engine_t *engine = (engine_t *)(intptr_t)handle;
    AndroidBitmapInfo info;
    void *rgba;
    if (AndroidBitmap_getInfo(env, bitmap, &info) || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail(env, "Invalid bitmap format");
        return NULL;
    }
    size_t size = (size_t)info.width * info.height;
    if (size > engine->capacity) {
        uint8_t *buffer = realloc(engine->gray, size);
        if (!buffer) {
            fail(env, "Cannot allocate grayscale image");
            return NULL;
        }
        engine->gray = buffer;
        engine->capacity = size;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &rgba)) {
        fail(env, "Cannot read bitmap");
        return NULL;
    }
    for (uint32_t y = 0; y < info.height; ++y) {
        const uint8_t *src = (const uint8_t *)rgba + y * info.stride;
        uint8_t *dst = engine->gray + y * info.width;
        uint32_t x = 0;
        for (; x + 8 <= info.width; x += 8) {
            uint8x8x4_t c = vld4_u8(src + x * 4);
            uint16x8_t luma = vmull_u8(c.val[0], vdup_n_u8(77));
            luma = vmlal_u8(luma, c.val[1], vdup_n_u8(150));
            luma = vmlal_u8(luma, c.val[2], vdup_n_u8(29));
            vst1_u8(dst + x, vrshrn_n_u16(luma, 8));
        }
        for (; x < info.width; ++x) {
            dst[x] = (77 * src[x*4] + 150 * src[x*4+1] + 29 * src[x*4+2] + 128) >> 8;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return detect(env, engine, engine->gray, info.width, info.height, family, threads, decimate, board);
}
