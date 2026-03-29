#include <jni.h>
#include <string>
#include <android/log.h>
#include "Processing.NDI.Lib.h"

#define TAG "NDICameraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static NDIlib_send_instance_t g_pNDI_send = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ndi_1camera_MainActivity_startNDISend(JNIEnv* env, jobject /* this */, jstring name) {
    if (g_pNDI_send) return JNI_TRUE;

    const char* nativeName = env->GetStringUTFChars(name, nullptr);

    // NDI 초기화
    if (!NDIlib_initialize()) {
        LOGE("Cannot initialize NDI");
        env->ReleaseStringUTFChars(name, nativeName);
        return JNI_FALSE;
    }

    // NDI 전송 설정
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
Java_com_example_ndi_1camera_MainActivity_stopNDISend(JNIEnv* env, jobject /* this */) {
    if (g_pNDI_send) {
        NDIlib_send_destroy(g_pNDI_send);
        g_pNDI_send = nullptr;
        NDIlib_destroy();
        LOGI("NDI Sender stopped");
    }
}
