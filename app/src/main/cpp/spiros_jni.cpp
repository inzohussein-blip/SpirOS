// SpirOS native bridge.
// Kotlin side: app/src/main/java/com/spiros/app/engine/SpirEngine.kt
#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "SpirEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

const char* hostAbi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#else
    return "unknown";
#endif
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_spiros_app_engine_SpirEngine_nativeVersion(JNIEnv* env, jobject /* this */) {
    std::string info = std::string(SPIROS_ENGINE_VERSION) + " (" + hostAbi() + ")";
    LOGI("engine loaded: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

// Track B1 replaces this with a real QEMU launch on a worker thread.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_spiros_app_engine_SpirEngine_nativeIsVmSupported(JNIEnv* /* env */, jobject /* this */) {
    return JNI_FALSE;
}
