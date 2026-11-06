#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <memory>
#include <thread>
#include "mediacodec_sink.h"
#include "matroska_demuxer.h"
#include "ass_renderer.h"

#define TAG "JNI_BRIDGE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct NativeEngineContext {
    std::unique_ptr<MediaCodecSink> video_sink;
    std::unique_ptr<MatroskaDemuxer> demuxer;
    std::unique_ptr<AssRenderer> sub_renderer;
    ANativeWindow* window = nullptr;
    std::thread pipeline_feeder_thread;
    std::atomic<bool> is_feeding{false};
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_player_coremedia_player_NativePlayerBridge_nativeCreate(JNIEnv* env, jobject thiz) {
    auto ctx = new NativeEngineContext();
    ctx->video_sink = std::make_unique<MediaCodecSink>();
    ctx->demuxer = std::make_unique<MatroskaDemuxer>();
    ctx->sub_renderer = std::make_unique<AssRenderer>();
    LOGI("NativePlayerContext creado.");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_player_coremedia_player_NativePlayerBridge_nativeSetSurface(
    JNIEnv* env, jobject thiz, jlong handle, jobject surface) {
    auto ctx = reinterpret_cast<NativeEngineContext*>(handle);
    if (!ctx) return;

    if (ctx->window) {
        ANativeWindow_release(ctx->window);
        ctx->window = nullptr;
    }

    if (surface) {
        ctx->window = ANativeWindow_fromSurface(env, surface);
        LOGI("Superficie nativa asignada: %p", ctx->window);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_player_coremedia_player_NativePlayerBridge_nativeOpenUrl(
    JNIEnv* env, jobject thiz, jlong handle, jstring jurl) {
    auto ctx = reinterpret_cast<NativeEngineContext*>(handle);
    if (!ctx || !jurl) return JNI_FALSE;

    const char* url_cstr = env->GetStringUTFChars(jurl, nullptr);
    bool ok = ctx->demuxer->open(url_cstr);
    env->ReleaseStringUTFChars(jurl, url_cstr);

    if (ok && ctx->window) {
        const auto& meta = ctx->demuxer->get_metadata();
        ctx->video_sink->configure(ctx->window, meta.width, meta.height, meta.csd0);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_player_coremedia_player_NativePlayerBridge_nativePlay(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto ctx = reinterpret_cast<NativeEngineContext*>(handle);
    if (!ctx) return;

    ctx->video_sink->start();
    ctx->demuxer->start();

    if (!ctx->is_feeding) {
        ctx->is_feeding = true;
        ctx->pipeline_feeder_thread = std::thread([ctx]() {
            MediaPacket packet;
            while (ctx->is_feeding) {
                if (ctx->demuxer->get_video_queue().pop(packet)) {
                    // Pasar paquete procesado al hardware MediaCodec
                    while (ctx->is_feeding && !ctx->video_sink->feed_packet(packet)) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(2));
                    }
                }
            }
        });
    }

    LOGI("Reproducción iniciada.");
}

JNIEXPORT void JNICALL
Java_com_player_coremedia_player_NativePlayerBridge_nativePause(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto ctx = reinterpret_cast<NativeEngineContext*>(handle);
    if (!ctx) return;

    ctx->is_feeding = false;
    if (ctx->pipeline_feeder_thread.joinable()) {
        ctx->pipeline_feeder_thread.join();
    }
    ctx->video_sink->stop();
    ctx->demuxer->stop();
    LOGI("Reproducción pausada.");
}

JNIEXPORT jlong JNICALL
Java_com_player_coremedia_player_NativePlayerBridge_nativeGetCurrentPts(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto ctx = reinterpret_cast<NativeEngineContext*>(handle);
    if (!ctx) return 0;
    return ctx->video_sink->get_current_pts_us();
}

JNIEXPORT void JNICALL
Java_com_player_coremedia_player_NativePlayerBridge_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto ctx = reinterpret_cast<NativeEngineContext*>(handle);
    if (!ctx) return;

    ctx->is_feeding = false;
    if (ctx->pipeline_feeder_thread.joinable()) {
        ctx->pipeline_feeder_thread.join();
    }

    if (ctx->window) {
        ANativeWindow_release(ctx->window);
    }

    delete ctx;
    LOGI("NativePlayerContext destruido.");
}

JNIEXPORT jint JNICALL
Java_com_player_coremedia_player_RemuxExoPlayerFactory_nativeFilterHevcNaluInPlace(
    JNIEnv* env, jclass clazz, jobject byte_buffer, jint position, jint limit) {
    if (!byte_buffer || limit <= position) return limit;

    uint8_t* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(byte_buffer));
    if (!ptr) return limit;

    static thread_local HevcNaluParser parser;
    size_t in_size = static_cast<size_t>(limit - position);
    size_t out_size = parser.process_frame_in_place(ptr + position, in_size);
    return static_cast<jint>(position + out_size);
}

}
