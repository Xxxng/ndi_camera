#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <algorithm>
#include <mutex>
#include "Processing.NDI.Lib.h"

#define TAG "NDICameraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static NDIlib_send_instance_t g_pNDI_send = nullptr;

// 핑퐁(더블) 버퍼 시스템
static std::vector<uint8_t> g_buffer_a;
static std::vector<uint8_t> g_buffer_b;
static bool g_use_buffer_a = true;
static std::mutex g_send_mutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ndi_1camera_MainActivity_startNDISend(JNIEnv* env, jobject /* this */, jstring name) {
    std::lock_guard<std::mutex> lock(g_send_mutex);
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

    LOGI("NDI Sender started successfully with Async support");
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

    std::lock_guard<std::mutex> lock(g_send_mutex);
    if (!g_pNDI_send) return;

    uint8_t* p_y = (uint8_t*)env->GetDirectBufferAddress(y_buffer);
    uint8_t* p_u = (uint8_t*)env->GetDirectBufferAddress(u_buffer);
    uint8_t* p_v = (uint8_t*)env->GetDirectBufferAddress(v_buffer);

    if (!p_y || !p_u || !p_v) return;

    // 현재 사용할 버퍼 선택 (Ping-pong)
    std::vector<uint8_t>& current_buffer = g_use_buffer_a ? g_buffer_a : g_buffer_b;
    if (current_buffer.size() < (size_t)(width * height * 2)) {
        current_buffer.resize(width * height * 2);
    }

    uint8_t* p_dst = current_buffer.data();

    // YUV420 to UYVY 변환 루프 (UYVY는 픽셀당 2바이트)
    // UYVY 구조: [U0, Y0, V0, Y1], [U1, Y2, V1, Y3] ...
    for (int y = 0; y < height; y++) {
        uint8_t* dst_line = p_dst + (y * width * 2);
        for (int x = 0; x < width; x += 2) {
            int y_idx1 = y * y_stride + x;
            int y_idx2 = y * y_stride + (x + 1);
            int uv_idx = (y / 2) * u_stride + (x / 2) * pixel_stride;

            // UYVY 포맷 채우기
            dst_line[x * 2 + 0] = p_u[uv_idx]; // U
            dst_line[x * 2 + 1] = p_y[y_idx1]; // Y0
            dst_line[x * 2 + 2] = p_v[uv_idx]; // V
            dst_line[x * 2 + 3] = p_y[y_idx2]; // Y1
        }
    }

    NDIlib_video_frame_v2_t video_frame;
    video_frame.xres = width;
    video_frame.yres = height;
    video_frame.FourCC = NDIlib_FourCC_type_UYVY;
    video_frame.p_data = p_dst;
    video_frame.line_stride_in_bytes = width * 2;

    // 비동기 전송 사용 (가이드 권장 사항)
    // 이 함수는 즉시 반환되며, NDI SDK가 백그라운드에서 전송을 처리합니다.
    NDIlib_send_send_video_v2_async(g_pNDI_send, &video_frame);

    // 다음 프레임을 위해 버퍼 교체
    g_use_buffer_a = !g_use_buffer_a;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ndi_1camera_MainActivity_stopNDISend(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_send_mutex);
    if (g_pNDI_send) {
        // 비동기 전송이 완료될 때까지 기다리기 위해 NULL 프레임 전달 (동기화)
        NDIlib_send_send_video_v2_async(g_pNDI_send, nullptr);
        
        NDIlib_send_destroy(g_pNDI_send);
        g_pNDI_send = nullptr;
        NDIlib_destroy();
        LOGI("NDI Sender stopped and resources cleared");
    }
}
