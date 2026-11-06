#include "matroska_demuxer.h"
#include <android/log.h>
#include <chrono>

#define TAG "MatroskaDemuxer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

MatroskaDemuxer::MatroskaDemuxer() = default;

MatroskaDemuxer::~MatroskaDemuxer() {
    stop();
}

bool MatroskaDemuxer::open(const std::string& url) {
    url_ = url;
    LOGI("Apertura de stream DirectPlay desde URL: %s", url_.c_str());

    // Inicialización de metadatos 4K UHD por defecto
    metadata_.width = 3840;
    metadata_.height = 2160;
    metadata_.audio_channels = 8;
    metadata_.audio_sample_rate = 192000;
    metadata_.video_track_index = 0;
    metadata_.audio_track_index = 1;
    metadata_.subtitle_track_index = 2;

    // CSD-0 mínimo de inicialización HEVC (VPS/SPS/PPS sintético o extraído del contenedor)
    // En una integración de producción con FFmpeg se extrae de AVCodecParameters.extradata
    metadata_.csd0 = {
        0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x0c, 0x01, // VPS
        0xff, 0xff, 0x01, 0x60, 0x00, 0x00, 0x03, 0x00,
        0xb0, 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00,
        0x7b, 0xac, 0x09
    };

    return true;
}

void MatroskaDemuxer::start() {
    if (!is_running_) {
        is_running_ = true;
        video_queue_.flush();
        demux_thread_ = std::thread(&MatroskaDemuxer::demux_loop, this);
        LOGI("Hilo de demuxing iniciado.");
    }
}

void MatroskaDemuxer::stop() {
    if (is_running_) {
        is_running_ = false;
        video_queue_.stop();
        if (demux_thread_.joinable()) {
            demux_thread_.join();
        }
        LOGI("Hilo de demuxing detenido.");
    }
}

void MatroskaDemuxer::seek(int64_t target_pts_us) {
    LOGI("Seek solicitado a: %lld us", (long long)target_pts_us);
    video_queue_.flush();
}

void MatroskaDemuxer::demux_loop() {
    // Simulación del bucle de paquetes con sincronización de reloj de red
    // En runtime conectado a FFmpeg, aquí se invoca av_read_frame()
    int64_t current_pts = 0;
    const int64_t frame_duration_us = 41708; // ~23.976 fps

    while (is_running_) {
        // Paquete de demostración con NALU 62 Dolby Vision de prueba
        std::vector<uint8_t> dummy_hevc_packet = {
            // NAL 32 (VPS)
            0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x0c, 0x01,
            // NAL 62 (Dolby Vision RPU)
            0x00, 0x00, 0x00, 0x01, 0x7c, 0x01, 0x00, 0x10, 0x08, 0x01,
            // NAL 63 (FEL - a descartar)
            0x00, 0x00, 0x00, 0x01, 0x7e, 0x01, 0xaa, 0xbb, 0xcc,
            // NAL 19 (IDR slice)
            0x00, 0x00, 0x00, 0x01, 0x26, 0x01, 0xaf, 0x00
        };

        bool pushed = video_queue_.push(
            dummy_hevc_packet.data(),
            dummy_hevc_packet.size(),
            current_pts,
            current_pts,
            true
        );

        if (!pushed) {
            break; // Detención solicitada
        }

        // Si hay audio callback conectado, despachar paquete de audio simulado
        if (audio_callback_) {
            uint8_t dummy_audio[512] = {0};
            audio_callback_(dummy_audio, sizeof(dummy_audio), current_pts);
        }

        current_pts += frame_duration_us;
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}
