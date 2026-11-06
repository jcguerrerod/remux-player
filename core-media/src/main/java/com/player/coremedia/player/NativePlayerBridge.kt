package com.player.coremedia.player

import android.view.Surface

/**
 * JNI Binding con el motor nativo en C++20 / Rust.
 */
class NativePlayerBridge {

    companion object {
        init {
            System.loadLibrary("native_pipeline")
        }
    }

    private var nativeHandle: Long = 0

    init {
        nativeHandle = nativeCreate()
    }

    fun setSurface(surface: Surface?) {
        if (nativeHandle != 0L) {
            nativeSetSurface(nativeHandle, surface)
        }
    }

    fun openUrl(url: String): Boolean {
        return if (nativeHandle != 0L) {
            nativeOpenUrl(nativeHandle, url)
        } else false
    }

    fun play() {
        if (nativeHandle != 0L) nativePlay(nativeHandle)
    }

    fun pause() {
        if (nativeHandle != 0L) nativePause(nativeHandle)
    }

    fun getCurrentPtsUs(): Long {
        return if (nativeHandle != 0L) nativeGetCurrentPts(nativeHandle) else 0L
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    // Declaraciones nativas JNI
    private external fun nativeCreate(): Long
    private external fun nativeSetSurface(handle: Long, surface: Surface?)
    private external fun nativeOpenUrl(handle: Long, url: String): Boolean
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeGetCurrentPts(handle: Long): Long
    private external fun nativeDestroy(handle: Long)
}
