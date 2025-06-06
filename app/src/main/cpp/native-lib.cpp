#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "hev_tun2socks_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_vpntest_NativeBridge_stringFromJNI(JNIEnv* env, jobject /* this */) {
    LOGI("JNI stringFromJNI called");
    return env->NewStringUTF("Hello from hev-socks5-tunnel native stub!");
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called");
    return JNI_VERSION_1_6;
}