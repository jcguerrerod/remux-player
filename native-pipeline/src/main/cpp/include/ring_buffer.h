#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <vector>
#include <mutex>
#include <condition_variable>
#include <cstdint>
#include <cstring>

/**
 * Ring buffer preasignado para intercambio de paquetes de medios
 * entre hilos de demuxing y decodificación sin fragmentación de heap.
 */
struct MediaPacket {
    std::vector<uint8_t> data;
    size_t size = 0;
    int64_t pts_us = 0;
    int64_t dts_us = 0;
    bool is_keyframe = false;
    bool is_eos = false;

    void ensure_capacity(size_t cap) {
        if (data.capacity() < cap) {
            data.resize(cap);
        }
    }
};

template<size_t Capacity = 64>
class PacketRingBuffer {
private:
    MediaPacket slots_[Capacity];
    size_t head_ = 0;
    size_t tail_ = 0;
    size_t count_ = 0;
    bool stopped_ = false;

    std::mutex mutex_;
    std::condition_variable not_full_;
    std::condition_variable not_empty_;

public:
    PacketRingBuffer() {
        for (size_t i = 0; i < Capacity; ++i) {
            slots_[i].ensure_capacity(1024 * 1024); // Preasignar 1MB por ranura (típico para 4K I-frames)
        }
    }

    bool push(const uint8_t* data, size_t size, int64_t pts, int64_t dts, bool is_key, bool is_eos = false) {
        std::unique_lock<std::mutex> lock(mutex_);
        not_full_.wait(lock, [this]() { return count_ < Capacity || stopped_; });

        if (stopped_) return false;

        auto& slot = slots_[tail_];
        slot.ensure_capacity(size);
        std::memcpy(slot.data.data(), data, size);
        slot.size = size;
        slot.pts_us = pts;
        slot.dts_us = dts;
        slot.is_keyframe = is_key;
        slot.is_eos = is_eos;

        tail_ = (tail_ + 1) % Capacity;
        count_++;

        not_empty_.notify_one();
        return true;
    }

    bool pop(MediaPacket& out_packet) {
        std::unique_lock<std::mutex> lock(mutex_);
        not_empty_.wait(lock, [this]() { return count_ > 0 || stopped_; });

        if (count_ == 0 && stopped_) return false;

        auto& slot = slots_[head_];
        out_packet.ensure_capacity(slot.size);
        std::memcpy(out_packet.data.data(), slot.data.data(), slot.size);
        out_packet.size = slot.size;
        out_packet.pts_us = slot.pts_us;
        out_packet.dts_us = slot.dts_us;
        out_packet.is_keyframe = slot.is_keyframe;
        out_packet.is_eos = slot.is_eos;

        head_ = (head_ + 1) % Capacity;
        count_--;

        not_full_.notify_one();
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopped_ = true;
        not_full_.notify_all();
        not_empty_.notify_all();
    }

    void flush() {
        std::lock_guard<std::mutex> lock(mutex_);
        head_ = 0;
        tail_ = 0;
        count_ = 0;
        stopped_ = false;
        not_full_.notify_all();
    }

    size_t size() {
        std::lock_guard<std::mutex> lock(mutex_);
        return count_;
    }
};

#endif // RING_BUFFER_H
