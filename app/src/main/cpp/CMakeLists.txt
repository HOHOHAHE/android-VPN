cmake_minimum_required(VERSION 3.10.2)

project("hev_tun2socks_android")

add_library(
    native-lib
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    native-lib
    ${log-lib}
)