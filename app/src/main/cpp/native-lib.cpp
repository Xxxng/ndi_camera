#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <algorithm>
#include "Processing.NDI.Lib.h"

#define TAG "NDICameraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static NDIlib_send_instance_t g_pNDI_send = nullptr;
static std::vector<uint8_t> g_rgba_buffer;

inline uint8_t clamp(int v) {
    return (v < 0) ? 0 : (v > 255) ? 255 : (uint8_t)v;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ndi_1camera_MainActivity_startNDISend(JNIEnv* env, jobject /* this */, jstring name) {
    if (g_pNDI_send) return JNI_TRUE;

    const char* nativeName = env->GetStringUTFChars(name, nullptr);

    if (!NDIlib_initialize()) {
        LOGE("Cannot initialize NDI");
        env->ReleaseStringUTFChars(name, nativeName);
        return JNI_FALSE;
    }

    NDIlib_send_create_t send_settings;
    send_settings.p_ndi_name = nativeName;
    send_settings.p_groups = nullptr;
    send_settings.clock_video = true;
    send_settings.clock_audio = false;

    g_pNDI_send = NDIlib_send_create(&send_settings);
    
    env->ReleaseStringUTFChars(name, nativeName);

    if (!g_pNDI_send) {
        LOGE("Cannot create NDI sender");
        return JNI_FALSE;
    }

    LOGI("NDI Sender started successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ndi_1camera_MainActivity_sendVideoFrame(
        JNIEnv* env, jobject /* this */,
        jobject y_buffer, jint y_stride,
        jobject u_buffer, jint u_stride,
        jobject v_buffer, jint v_stride,
        jint pixel_stride,
        jint width, jint height) {

    if (!g_pNDI_send) return;

    uint8_t* p_y = (uint8_t*)env->GetDirectBufferAddress(y_buffer);
    uint8_t* p_u = (uint8_t*)env->GetDirectBufferAddress(u_buffer);
    uint8_t* p_v = (uint8_t*)env->GetDirectBufferAddress(v_buffer);

    if (!p_y || !p_u || !p_v) return;

    // RGBA 버퍼 준비
    if (g_rgba_buffer.size() < (size_t)(width * height * 4)) {
        g_rgba_buffer.resize(width * height * 4);
    }

    uint8_t* p_rgba = g_rgba_buffer.data();

    // YUV420 to RGBA 변환 루프 (Fast Integer Arithmetic)
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int y_idx = y * y_stride + x;
            int uv_idx = (y / 2) * u_stride + (x / 2) * pixel_stride;

            int Y = p_y[y_idx];
            int U = p_u[uv_idx] - 128;
            int V = p_v[uv_idx] - 128;

            // BT.601 conversion
            int R = Y + ((1436 * V) >> 10);
            int G = Y - ((352 * U + 731 * V) >> 10);
            int B = Y + ((1814 * U) >> 10);

            int rgba_idx = (y * width + x) * 4;
            p_rgba[rgba_idx + 0] = clamp(R); // R
            p_rgba[rgba_idx + 1] = clamp(G); // G
            p_rgba[rgba_idx + 2] = clamp(B); // B
            p_rgba[rgba_idx + 3] = 255;      // A
        }
    }

    NDIlib_video_frame_v2_t video_frame;
    video_frame.xres = width;
    video_frame.yres = height;
    video_frame.FourCC = NDIlib_FourCC_type_RGBA;
    video_frame.p_data = p_rgba;
    video_frame.line_stride_in_bytes = width * 4;

    NDIlib_send_send_video_v2(g_pNDI_send, &video_frame);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ndi_1camera_MainActivity_stopNDISend(JNIEnv* env, jobject /* this */) {
    if (g_pNDI_send) {
        NDIlib_send_destroy(g_pNDI_send);
        g_pNDI_send = nullptr;
        NDIlib_destroy();
        LOGI("NDI Sender stopped");
    }
}
