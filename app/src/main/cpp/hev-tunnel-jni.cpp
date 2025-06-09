#include <jni.h>
#include <unistd.h>
#include <android/log.h>
#include <pthread.h>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "HevTunnelJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 引入實際的 hev-socks5-tunnel 函數
extern "C" {
    int hev_main(int argc, char *argv[]);
    void hev_stop(void);
    int hev_is_running(void);
}

static pthread_t tunnel_thread;
static bool tunnel_running = false;

typedef struct {
    int tun_fd;
    char config_path[256];
} tunnel_args_t;


static void* tunnel_thread_func(void* arg) {
    tunnel_args_t* args = (tunnel_args_t*)arg;
    
    // 設置環境變數傳遞 TUN fd
    char fd_str[16];
    snprintf(fd_str, sizeof(fd_str), "%d", args->tun_fd);
    setenv("HEV_TUN_FD", fd_str, 1);
    
    // 準備 argv
    char* argv[] = {
        const_cast<char*>("hev-socks5-tunnel"),
        args->config_path,
        nullptr
    };
    
    LOGD("Starting hev-tunnel with config: %s", args->config_path);
    int result = hev_main(2, argv);
    LOGD("hev-tunnel exited with code: %d", result);
    
    tunnel_running = false;
    free(args);
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_startTunnelNative(
    JNIEnv *env, jobject thiz, jint tun_fd, jstring config_path) {
    
    if (tunnel_running) {
        LOGE("Tunnel already running");
        return -1;
    }
    
    const char *config_str = env->GetStringUTFChars(config_path, nullptr);
    
    tunnel_args_t* args = (tunnel_args_t*)malloc(sizeof(tunnel_args_t));
    args->tun_fd = tun_fd;
    strncpy(args->config_path, config_str, sizeof(args->config_path) - 1);
    args->config_path[sizeof(args->config_path) - 1] = '\0';
    
    env->ReleaseStringUTFChars(config_path, config_str);
    
    int result = pthread_create(&tunnel_thread, nullptr, tunnel_thread_func, args);
    if (result == 0) {
        tunnel_running = true;
        LOGD("Tunnel thread created successfully");
        return 0;
    } else {
        LOGE("Failed to create tunnel thread: %d", result);
        free(args);
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_stopTunnelNative(
    JNIEnv *env, jobject thiz) {
    
    if (!tunnel_running) {
        LOGD("Tunnel not running");
        return;
    }
    
    LOGD("Stopping tunnel...");
    hev_stop();
    pthread_join(tunnel_thread, nullptr);
    tunnel_running = false;
    LOGD("Tunnel stopped");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_isRunningNative(
    JNIEnv *env, jobject thiz) {
    return tunnel_running && hev_is_running();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_getTunnelStatsNative(
    JNIEnv *env, jobject thiz) {
    
    // 這裡可以添加統計資訊的獲取
    // 暫時返回基本狀態
    char stats_buffer[512];
    snprintf(stats_buffer, sizeof(stats_buffer),
             "Tunnel Running: %s\nThread Running: %s",
             hev_is_running() ? "true" : "false",
             tunnel_running ? "true" : "false");
    
    return env->NewStringUTF(stats_buffer);
}