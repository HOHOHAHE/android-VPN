package com.example.vpntest

object NativeBridge {
    init {
        System.loadLibrary("native-lib")
    }

    external fun stringFromJNI(): String
}