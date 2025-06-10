cmake_minimum_required(VERSION 3.22.1)
project("hev-tunnel-bridge")

# 設置標準
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

# 添加 hev-socks5-tunnel 靜態庫
add_library(
    hev-socks5-tunnel-static
    STATIC
    hev-socks5-tunnel/hev-main.c
    hev-socks5-tunnel/hev-tunnel.c
    hev-socks5-tunnel/hev-config.c
)

# 為 hev-socks5-tunnel 設置 include 目錄
target_include_directories(
    hev-socks5-tunnel-static
    PRIVATE
    hev-socks5-tunnel
)

# 創建 JNI 橋接庫
add_library(
    hev-tunnel-bridge
    SHARED
    native-lib.cpp
    hev-tunnel-jni.cpp
)

# 為 JNI 橋接庫設置 include 目錄
target_include_directories(
    hev-tunnel-bridge
    PRIVATE
    hev-socks5-tunnel
)

# 查找必要的庫
find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

# 連結庫
target_link_libraries(
    hev-tunnel-bridge
    hev-socks5-tunnel-static
    ${android-lib}
    ${log-lib}
)

target_compile_options(hev-tunnel-bridge PRIVATE -Wall -Wextra)

# 保持舊的 native-lib 為了向後相容
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

target_link_libraries(
    native-lib
    ${log-lib}
)