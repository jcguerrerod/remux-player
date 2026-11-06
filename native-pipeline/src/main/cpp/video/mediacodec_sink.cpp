#include "mediacodec_sink.h"
#include <android/log.h>
#include <chrono>

#define TAG "MediaCodecSink"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

MediaCodecSink::MediaCodecSink() = default;

MediaCodecSink::~MediaCodecSink() {
    stop();
}

bool MediaCodecSink::configure(
    ANativeWindow* window,
    int32_t width,
    int32_t height,
    const std::vector<uint8_t>& csd0
) {
    stop();
    window_ = window;

    // 1. Crear decodificador por tipo MIME Dolby Vision
    codec_ = AMediaCodec_createDecoderByType("video/dolby-vision");
    if (!codec_) {
        LOGW("Decoder video/dolby-vision no encontrado directamente, intentando video/hevc...");
        codec_ = AMediaCodec_createDecoderByType("video/hevc");
    }

    if (!codec_) {
        LOGE("Error crítico: No existe decodificador de hardware compatible en este SoC.");
        return false;
    }

    // 2. Construir MediaFormat estricto para activar la bandera DV en el panel
    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/dolby-vision");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);

    // Profile 8 (DolbyVisionProfileDvheSt = 32 / 0x20)
    AMediaFormat_setInt32(format, "profile", 32);
    // Level 09 (UHD 60fps) = 64
    AMediaFormat_setInt32(format, "level", 64);

    // Espacio de color y curva electro-óptica (EOTF) PQ ST 2084
    AMediaFormat_setInt32(format, "color-standard", 6); // COLOR_STANDARD_BT2020
    AMediaFormat_setInt32(format, "color-transfer", 6); // COLOR_TRANSFER_ST2084
    AMediaFormat_setInt32(format, "color-range", 2);    // COLOR_RANGE_LIMITED

    // Vendor flags para SoCs MediaTek / Realtek / Amlogic
    AMediaFormat_setInt32(format, "vendor.dolby.profile", 8);
    AMediaFormat_setInt32(format, "vendor.dolby.compatibility", 1);
    AMediaFormat_setInt32(format, "low-latency", 0);

    // CSD-0 (VPS/SPS/PPS)
    if (!csd0.empty()) {
        AMediaFormat_setBuffer(format, "csd-0", csd0.data(), csd0.size());
    }

    media_status_t status = AMediaCodec_configure(codec_, format, window_, nullptr, 0);
    AMediaFormat_delete(format);

    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure falló con código: %d", status);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    LOGI("MediaCodec configurado exitosamente para Dolby Vision P8.1 [%dx%d]", width, height);
    return true;
}

void MediaCodecSink::start() {
    if (codec_ && !is_running_) {
        media_status_t status = AMediaCodec_start(codec_);
        if (status == AMEDIA_OK) {
            is_running_ = true;
            render_thread_ = std::thread(&MediaCodecSink::render_loop, this);
            LOGI("MediaCodec Sink iniciado.");
        } else {
            LOGE("Fallo al iniciar AMediaCodec: %d", status);
        }
    }
}

void MediaCodecSink::stop() {
    if (is_running_) {
        is_running_ = false;
        if (render_thread_.joinable()) {
            render_thread_.join();
        }
    }
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
}

void MediaCodecSink::flush() {
    if (codec_ && is_running_) {
        AMediaCodec_flush(codec_);
    }
}

bool MediaCodecSink::feed_packet(const MediaPacket& packet) {
    if (!codec_ || !is_running_) return false;

    // Timeout de 10ms esperando buffer de hardware disponible
    ssize_t buf_idx = AMediaCodec_dequeueInputBuffer(codec_, 10000);
    if (buf_idx < 0) {
        return false; // Backpressure: el decodificador está ocupado
    }

    size_t out_capacity = 0;
    uint8_t* hardware_buf = AMediaCodec_getInputBuffer(codec_, buf_idx, &out_capacity);

    if (!hardware_buf || out_capacity == 0) {
        return false;
    }

    if (packet.is_eos) {
        AMediaCodec_queueInputBuffer(codec_, buf_idx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        return true;
    }

    // Zero-Copy: El parser lee el paquete de red/demuxer y escribe directamente
    // en la memoria física del SoC asignada a hardware_buf
    size_t bytes_written = parser_.process_frame_zero_copy(
        packet.data.data(),
        packet.size,
        hardware_buf,
        out_capacity
    );

    uint32_t flags = packet.is_keyframe ? 0 : 0;
    media_status_t q_status = AMediaCodec_queueInputBuffer(
        codec_,
        buf_idx,
        0,
        bytes_written,
        packet.pts_us,
        flags
    );

    return (q_status == AMEDIA_OK);
}

void MediaCodecSink::render_loop() {
    AMediaCodecBufferInfo info;

    while (is_running_) {
        ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 5000); // 5ms poll

        if (out_idx >= 0) {
            current_pts_us_.store(info.presentationTimeUs);

            // Renderizar inmediatamente a la superficie física con hardware clock sync
            AMediaCodec_releaseOutputBuffer(codec_, out_idx, true);

            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                LOGI("MediaCodec EOS alcanzado.");
                break;
            }
        } else if (out_idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* new_fmt = AMediaCodec_getOutputFormat(codec_);
            LOGI("MediaCodec format change: %s", AMediaFormat_toString(new_fmt));
            AMediaFormat_delete(new_fmt);
        }
    }
}
