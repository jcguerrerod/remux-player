#ifndef MATROSKA_DEMUXER_H
#define MATROSKA_DEMUXER_H

#include <string>
#include <thread>
#include <atomic>
#include <vector>
#include <functional>
#include "ring_buffer.h"

struct StreamMetadata {
    int video_track_index = -1;
    int audio_track_index = -1;
    int subtitle_track_index = -1;

    int width = 3840;
    int height = 2160;
    int audio_channels = 8;
    int audio_sample_rate = 192000;
    int audio_codec_id = 0; // TrueHD / DTS-HD

    std::vector<uint8_t> csd0;
};

class MatroskaDemuxer {
public:
    using AudioPacketCallback = std::function<void(const uint8_t* data, size_t size, int64_t pts_us)>;
    using SubtitleCueCallback = std::function<void(int64_t start_ms, int64_t end_ms, const char* text)>;

    MatroskaDemuxer();
    ~MatroskaDemuxer();

    bool open(const std::string& url);
    void start();
    void stop();
    void seek(int64_t target_pts_us);

    PacketRingBuffer<64>& get_video_queue() { return video_queue_; }
    const StreamMetadata& get_metadata() const { return metadata_; }

    void set_audio_callback(AudioPacketCallback cb) { audio_callback_ = std::move(cb); }
    void set_subtitle_callback(SubtitleCueCallback cb) { subtitle_callback_ = std::move(cb); }

private:
    void demux_loop();

    std::string url_;
    std::atomic<bool> is_running_{false};
    std::thread demux_thread_;

    StreamMetadata metadata_;
    PacketRingBuffer<64> video_queue_;

    AudioPacketCallback audio_callback_;
    SubtitleCueCallback subtitle_callback_;
};

#endif // MATROSKA_DEMUXER_H
