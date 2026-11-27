#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>

#include "rnnoise.h"

enum { FRAME_48K = 480, FRAME_16K = 160 };

typedef struct {
    DenoiseState *cleaner;
    DenoiseState *detector;
    float previous_sample;
    float low_band;
} VoiceFilter;

JNIEXPORT jlong JNICALL
Java_com_drivevoice_app_service_RnNoiseNative_create(JNIEnv *env, jobject self, jboolean detection) {
    (void)env;
    (void)self;
    VoiceFilter *filter = calloc(1, sizeof(*filter));
    if (!filter) return 0;
    filter->cleaner = rnnoise_create(NULL);
    if (detection) filter->detector = rnnoise_create(NULL);
    if (!filter->cleaner || (detection && !filter->detector)) {
        if (filter->cleaner) rnnoise_destroy(filter->cleaner);
        if (filter->detector) rnnoise_destroy(filter->detector);
        free(filter);
        return 0;
    }
    return (jlong)(intptr_t)filter;
}

JNIEXPORT jfloat JNICALL
Java_com_drivevoice_app_service_RnNoiseNative_process(
        JNIEnv *env, jobject self, jlong handle, jshortArray input, jshortArray output,
        jint sample_rate, jboolean wind) {
    (void)self;
    VoiceFilter *filter = (VoiceFilter *)(intptr_t)handle;
    if (!filter || (sample_rate != 48000 && sample_rate != 16000)) return -1;
    const int length = sample_rate / 100;
    if ((*env)->GetArrayLength(env, input) != length || (*env)->GetArrayLength(env, output) != length) return -1;

    jshort pcm[FRAME_48K];
    float source[FRAME_48K];
    float clean[FRAME_48K];
    (*env)->GetShortArrayRegion(env, input, 0, length, pcm);
    if ((*env)->ExceptionCheck(env)) return -1;

    if (length == FRAME_48K) {
        for (int i = 0; i < FRAME_48K; i++) source[i] = pcm[i];
    } else {
        // Interpolate the 16 kHz device input without a discontinuity at frame boundaries.
        for (int i = 0; i < FRAME_16K; i++) {
            const float step = (pcm[i] - filter->previous_sample) / 3.f;
            for (int j = 0; j < 3; j++) source[3 * i + j] = filter->previous_sample + (j + 1) * step;
            filter->previous_sample = pcm[i];
        }
    }
    for (int i = 0; i < FRAME_48K; i++) {
        filter->low_band += .0117f * (source[i] - filter->low_band);
        if (wind) source[i] -= .6f * filter->low_band;
    }

    float probability = rnnoise_process_frame(filter->cleaner, clean, source);
    if (filter->detector) probability = rnnoise_process_frame(filter->detector, source, clean);
    for (int i = 0; i < length; i++) {
        const float sample = length == FRAME_48K ? clean[i]
            : (clean[3 * i] + 2.f * clean[3 * i + 1] + clean[3 * i + 2]) / 4.f;
        pcm[i] = (jshort)lrintf(fminf(32767.f, fmaxf(-32768.f, sample)));
    }
    (*env)->SetShortArrayRegion(env, output, 0, length, pcm);
    return probability;
}

JNIEXPORT void JNICALL
Java_com_drivevoice_app_service_RnNoiseNative_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    VoiceFilter *filter = (VoiceFilter *)(intptr_t)handle;
    if (!filter) return;
    rnnoise_destroy(filter->cleaner);
    if (filter->detector) rnnoise_destroy(filter->detector);
    free(filter);
}
