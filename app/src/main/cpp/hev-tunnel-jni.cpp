#include <jni.h>
#include <unistd.h>
#include <android/log.h>
#include <pthread.h>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "HevTunnelJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 引入官方 hev-socks5-tunnel API
extern "C" {
    #include "hev-main.h"
    #include "hev-socks5-tunnel.h"
    
    // 官方API函數
    int hev_socks5_tunnel_main_from_file(const char *config_path, int tun_fd);
    void hev_socks5_tunnel_quit(void);
    void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes,
                                  size_t *rx_packets, size_t *rx_bytes);
}

static pthread_t tunnel_thread;
static volatile bool tunnel_running = false;
static volatile bool tunnel_should_stop = false;

typedef struct {
    int tun_fd;
    char config_path[256];
} tunnel_args_t;

static void* tunnel_thread_func(void* arg) {
    tunnel_args_t* args = (tunnel_args_t*)arg;
    
    LOGI("Starting official hev-socks5-tunnel with config: %s, tun_fd: %d", 
         args->config_path, args->tun_fd);
    
    // 使用官方API啟動tunnel
    int result = hev_socks5_tunnel_main_from_file(args->config_path, args->tun_fd);
    
    LOGI("Official hev-socks5-tunnel exited with code: %d", result);
    
    tunnel_running = false;
    tunnel_should_stop = false;
    free(args);
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_startTunnelNative(
    JNIEnv *env, jobject thiz, jint tun_fd, jstring config_path) {
    
    (void)thiz;  // 消除未使用參數警告
    
    if (tunnel_running) {
        LOGE("Tunnel already running");
        return -1;
    }
    
    if (tun_fd < 0) {
        LOGE("Invalid TUN file descriptor: %d", tun_fd);
        return -2;
    }
    
    const char *config_str = env->GetStringUTFChars(config_path, nullptr);
    if (!config_str) {
        LOGE("Failed to get config path string");
        return -3;
    }
    
    // 驗證配置檔案是否存在
    if (access(config_str, R_OK) != 0) {
        LOGE("Config file not accessible: %s", config_str);
        env->ReleaseStringUTFChars(config_path, config_str);
        return -4;
    }
    
    tunnel_args_t* args = (tunnel_args_t*)malloc(sizeof(tunnel_args_t));
    if (!args) {
        LOGE("Failed to allocate memory for tunnel arguments");
        env->ReleaseStringUTFChars(config_path, config_str);
        return -5;
    }
    
    args->tun_fd = tun_fd;
    strncpy(args->config_path, config_str, sizeof(args->config_path) - 1);
    args->config_path[sizeof(args->config_path) - 1] = '\0';
    
    env->ReleaseStringUTFChars(config_path, config_str);
    
    tunnel_should_stop = false;
    
    int result = pthread_create(&tunnel_thread, nullptr, tunnel_thread_func, args);
    if (result == 0) {
        tunnel_running = true;
        LOGI("Official tunnel thread created successfully");
        
        // 給線程一點時間啟動
        usleep(100000); // 100ms
        
        return 0;
    } else {
        LOGE("Failed to create tunnel thread: %d", result);
        free(args);
        return -6;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_stopTunnelNative(
    JNIEnv *env, jobject thiz) {
    
    (void)env;    // 消除未使用參數警告
    (void)thiz;
    
    if (!tunnel_running) {
        LOGD("Tunnel not running");
        return;
    }
    
    LOGI("Stopping official hev-socks5-tunnel...");
    tunnel_should_stop = true;
    
    // 使用官方API停止tunnel
    hev_socks5_tunnel_quit();
    
    // Android不支持pthread_timedjoin_np，使用簡單的join
    int join_result = pthread_join(tunnel_thread, nullptr);
    if (join_result == 0) {
        LOGI("Tunnel thread joined successfully");
    } else {
        LOGE("Tunnel thread join failed: %d", join_result);
    }
    
    tunnel_running = false;
    tunnel_should_stop = false;
    LOGI("Official tunnel stopped");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_isRunningNative(
    JNIEnv *env, jobject thiz) {
    
    (void)env;   // 消除未使用參數警告
    (void)thiz;
    
    return tunnel_running && !tunnel_should_stop;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_getTunnelStatsNative(
    JNIEnv *env, jobject thiz) {
    
    (void)thiz;  // 消除未使用參數警告
    
    char stats_buffer[1024];
    
    if (tunnel_running && !tunnel_should_stop) {
        // 嘗試獲取官方統計資訊
        size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
        
        // 注意：這個函數可能需要tunnel正在運行才能獲取統計
        hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
        
        snprintf(stats_buffer, sizeof(stats_buffer),
                "Status: Running\n"
                "TX Packets: %zu\n"
                "TX Bytes: %zu\n"
                "RX Packets: %zu\n"
                "RX Bytes: %zu\n"
                "Thread Running: %s\n"
                "Should Stop: %s",
                tx_packets, tx_bytes, rx_packets, rx_bytes,
                tunnel_running ? "true" : "false",
                tunnel_should_stop ? "true" : "false");
    } else {
        snprintf(stats_buffer, sizeof(stats_buffer),
                "Status: Stopped\n"
                "Thread Running: %s\n"
                "Should Stop: %s",
                tunnel_running ? "true" : "false",
                tunnel_should_stop ? "true" : "false");
    }
    
    return env->NewStringUTF(stats_buffer);
}

// 新增：獲取詳細錯誤訊息的JNI函數
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_getLastErrorNative(
    JNIEnv *env, jobject thiz) {
    
    (void)thiz;  // 消除未使用參數警告
    
    // 這裡可以返回最後的錯誤訊息
    // 目前返回基本狀態資訊
    char error_buffer[256];
    snprintf(error_buffer, sizeof(error_buffer),
            "Tunnel State - Running: %s, Should Stop: %s",
            tunnel_running ? "true" : "false",
            tunnel_should_stop ? "true" : "false");
    
    return env->NewStringUTF(error_buffer);
}

// 新增：強制重置tunnel狀態的JNI函數
extern "C" JNIEXPORT void JNICALL
Java_com_example_vpntest_hev_HevTunnelManager_forceResetNative(
    JNIEnv *env, jobject thiz) {
    
    (void)env;   // 消除未使用參數警告
    (void)thiz;
    
    LOGI("Force resetting tunnel state...");
    
    if (tunnel_running) {
        hev_socks5_tunnel_quit();
        // Android不支持pthread_cancel，只能等待線程自然結束
        pthread_join(tunnel_thread, nullptr);
    }
    
    tunnel_running = false;
    tunnel_should_stop = false;
    
    LOGI("Tunnel state force reset completed");
}