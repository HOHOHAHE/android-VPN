cmake_minimum_required(VERSION 3.22.1)
project("hev-tunnel-android" C CXX ASM)

# 設置編譯標準
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

# 啟用彙編語言
enable_language(ASM)

# 設置官方源碼路徑
set(HEV_TUNNEL_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/hev-socks5-tunnel/jni)

# 收集官方源檔案 - 包含JNI
file(GLOB_RECURSE HEV_TUNNEL_SOURCES
    "${HEV_TUNNEL_ROOT}/src/*.c"
    "${HEV_TUNNEL_ROOT}/src/misc/*.c"
    "${HEV_TUNNEL_ROOT}/src/core/*.c"
)

# 排除不需要的平台特定檔案
list(FILTER HEV_TUNNEL_SOURCES EXCLUDE REGEX ".*hev-tunnel-freebsd\\.c$")
list(FILTER HEV_TUNNEL_SOURCES EXCLUDE REGEX ".*hev-tunnel-macos\\.c$")

# 收集第三方庫源檔案
file(GLOB YAML_SOURCES
    "${HEV_TUNNEL_ROOT}/third-part/yaml/src/*.c"
)

file(GLOB_RECURSE LWIP_SOURCES
    "${HEV_TUNNEL_ROOT}/third-part/lwip/src/core/*.c"
    "${HEV_TUNNEL_ROOT}/third-part/lwip/src/core/ipv4/*.c"
    "${HEV_TUNNEL_ROOT}/third-part/lwip/src/core/ipv6/*.c"
    "${HEV_TUNNEL_ROOT}/third-part/lwip/src/netif/*.c"
    "${HEV_TUNNEL_ROOT}/third-part/lwip/src/api/*.c"
    "${HEV_TUNNEL_ROOT}/third-part/lwip/src/ports/lib/*.c"
    "${HEV_TUNNEL_ROOT}/third-part/lwip/src/ports/unix/lib/*.c"
)

file(GLOB_RECURSE HEV_TASK_SOURCES
    "${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/*.c"
)

# 添加架構特定的彙編檔案
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    list(APPEND HEV_TASK_SOURCES
        "${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/arm/hev-task-execute-aarch64.s"
    )
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    list(APPEND HEV_TASK_SOURCES
        "${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/arm/hev-task-execute-arm.s"
    )
elseif(${ANDROID_ABI} STREQUAL "x86")
    list(APPEND HEV_TASK_SOURCES
        "${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/x86/hev-task-execute-x86.s"
    )
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    list(APPEND HEV_TASK_SOURCES
        "${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/x86/hev-task-execute-x86_64.s"
    )
endif()

# 設置包含目錄
set(HEV_TUNNEL_INCLUDES
    ${HEV_TUNNEL_ROOT}/src
    ${HEV_TUNNEL_ROOT}/src/misc
    ${HEV_TUNNEL_ROOT}/src/core/include
    ${HEV_TUNNEL_ROOT}/third-part/yaml/include
    ${HEV_TUNNEL_ROOT}/third-part/lwip/src/ports/include
    ${HEV_TUNNEL_ROOT}/third-part/lwip/src/include
    ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/include
)

# Android特定編譯標誌
add_compile_definitions(
    FD_SET_DEFINED=1
    SOCKLEN_T_DEFINED=1
    COMMIT_ID="official-android"
)

# 創建YAML靜態庫
add_library(
    yaml
    STATIC
    ${YAML_SOURCES}
)

target_include_directories(
    yaml
    PRIVATE
    ${HEV_TUNNEL_ROOT}/third-part/yaml/include
)

target_compile_definitions(yaml PRIVATE
    YAML_VERSION_MAJOR=0
    YAML_VERSION_MINOR=2
    YAML_VERSION_PATCH=5
    YAML_VERSION_STRING="0.2.5"
)

# 創建LWIP靜態庫
add_library(
    lwip
    STATIC
    ${LWIP_SOURCES}
)

target_include_directories(
    lwip
    PRIVATE
    ${HEV_TUNNEL_ROOT}/third-part/lwip/src/include
    ${HEV_TUNNEL_ROOT}/third-part/lwip/src/ports/include
)

# 創建HEV Task System靜態庫
add_library(
    hev-task-system
    STATIC
    ${HEV_TASK_SOURCES}
)

target_include_directories(
    hev-task-system
    PRIVATE
    ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/include
    ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src
    ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/arm
    ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/x86
)

target_compile_definitions(hev-task-system PRIVATE
    CONFIG_SCHED_CLOCK=CLOCK_MONOTONIC
    HEV_HAVE_IO_SPLICE=0
    HEV_HAVE_IOURING=0
    CONFIG_MEMALLOC_SLICE_MAX_SIZE=8192
    CONFIG_MEMALLOC_SLICE_ALIGN=8
    CONFIG_MEMALLOC_SLICE_MAX_COUNT=1024
)

# 設置彙編檔案的編譯選項
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set_source_files_properties(
        ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/arm/hev-task-execute-aarch64.s
        PROPERTIES COMPILE_FLAGS "-x assembler-with-cpp"
    )
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set_source_files_properties(
        ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/arm/hev-task-execute-arm.s
        PROPERTIES COMPILE_FLAGS "-x assembler-with-cpp"
    )
elseif(${ANDROID_ABI} STREQUAL "x86")
    set_source_files_properties(
        ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/x86/hev-task-execute-x86.s
        PROPERTIES COMPILE_FLAGS "-x assembler-with-cpp"
    )
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set_source_files_properties(
        ${HEV_TUNNEL_ROOT}/third-part/hev-task-system/src/arch/x86/hev-task-execute-x86_64.s
        PROPERTIES COMPILE_FLAGS "-x assembler-with-cpp"
    )
endif()

# 創建 hev-tunnel-bridge 共享庫（包含JNI）
# 名稱修改為匹配 NativeBridge.kt 中的載入期望
add_library(
    hev-tunnel-bridge
    SHARED
    ${HEV_TUNNEL_SOURCES}
    hev-tunnel-jni.cpp
)

target_include_directories(
    hev-tunnel-bridge
    PRIVATE
    ${HEV_TUNNEL_INCLUDES}
)

target_compile_definitions(hev-tunnel-bridge PRIVATE
    FD_SET_DEFINED=1
    SOCKLEN_T_DEFINED=1
    COMMIT_ID="official-android"
    ANDROID=1
    PKGNAME=com/example/vpntest/hev
    CLSNAME=HevTunnelManager
)

# 設置ARM NEON支援
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(hev-tunnel-bridge PRIVATE -mfpu=neon)
endif()

# 查找必要的庫
find_library(log-lib log)
find_library(android-lib android)

# 連結配置
target_link_libraries(
    hev-tunnel-bridge
    yaml
    lwip
    hev-task-system
    ${log-lib}
)

# 保持舊的native-lib為了向後相容
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

target_link_libraries(
    native-lib
    ${log-lib}
)