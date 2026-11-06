#ifndef MEDIACODEC_SINK_H
#define MEDIACODEC_SINK_H

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#include <cstdint>
#include <vector>
#include <atomic>
#include <thread>
#include "hevc_nalu_parser.h"
#include "ring_buffer.h"

class MediaCodecSink {
public:
    MediaCodecSink();
    ~MediaCodecSink();

    /**
     * Inicializa y configura el decodificador de hardware para Dolby Vision Perfil 8.1.
     */
    bool configure(
        ANativeWindow* window,
        int32_t width,
        int32_t height,
        const std::vector<uint8_t>& csd0
    );

    void start();
    void stop();
    void flush();

    /**
     * Envía un paquete demuxado al decodificador a través del parser de NALUs.
     */
    bool feed_packet(const MediaPacket& packet);

    int64_t get_current_pts_us() const { return current_pts_us_.load(); }

private:
    void render_loop();

    AMediaCodec* codec_ = nullptr;
    ANativeWindow* window_ = nullptr;
    HevcNaluParser parser_;

    std::atomic<bool> is_running_{false};
    std::atomic<int64_t> current_pts_us_{0};
    std::thread render_thread_;
};

#endif // MEDIACODEC_SINK_H
