package com.spiros.app.engine

/** Kotlin face of the native engine in `libspiros.so` (see cpp/spiros_jni.cpp). */
object SpirEngine {

    private val loadError: Throwable? = runCatching { System.loadLibrary("spiros") }.exceptionOrNull()

    val isLoaded: Boolean get() = loadError == null

    fun version(): String = if (isLoaded) nativeVersion() else "unavailable (${loadError?.message})"

    fun isVmSupported(): Boolean = isLoaded && nativeIsVmSupported()

    private external fun nativeVersion(): String
    private external fun nativeIsVmSupported(): Boolean
}
