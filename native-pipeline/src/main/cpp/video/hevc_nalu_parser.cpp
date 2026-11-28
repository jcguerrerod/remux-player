#include "hevc_nalu_parser.h"
#include <cstring>
#include <android/log.h>

#define TAG "HevcNaluParser"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

/**
 * Fast search for Annex B start codes (0x00 0x00 0x01 or 0x00 0x00 0x00 0x01)
 * utilizing memchr to skip long non-zero video bitstream payload blocks in hardware.
 */
inline size_t find_next_start_code(const uint8_t* data, size_t offset, size_t size, size_t* out_code_len) {
    while (offset + 2 < size) {
        const uint8_t* zero_ptr = static_cast<const uint8_t*>(
            std::memchr(&data[offset], 0x00, size - offset - 2)
        );
        if (!zero_ptr) {
            break;
        }

        size_t idx = static_cast<size_t>(zero_ptr - data);
        if (data[idx + 1] == 0x00) {
            if (data[idx + 2] == 0x01) {
                if (out_code_len) *out_code_len = 3;
                return idx;
            }
            if (idx + 3 < size && data[idx + 2] == 0x00 && data[idx + 3] == 0x01) {
                if (out_code_len) *out_code_len = 4;
                return idx;
            }
        }
        offset = idx + 1;
    }
    return size;
}

} // namespace

HevcNaluParser::HevcNaluParser() {
    std::memset(rpu_scratch_buf_, 0, sizeof(rpu_scratch_buf_));
}

size_t HevcNaluParser::process_frame_zero_copy(
    const uint8_t* __restrict src,
    size_t src_size,
    uint8_t* __restrict dst,
    size_t dst_capacity
) {
    if (!src || !dst || src_size == 0 || dst_capacity == 0) {
        return 0;
    }

    size_t read_idx = 0;
    size_t write_idx = 0;

    while (read_idx + 3 <= src_size) {
        size_t start_code_len = 0;
        size_t start_idx = find_next_start_code(src, read_idx, src_size, &start_code_len);
        if (start_idx >= src_size || start_code_len == 0) {
            break;
        }

        size_t nalu_payload_start = start_idx + start_code_len;
        if (nalu_payload_start >= src_size) {
            break;
        }

        // Buscar el límite del NALU actual localizando el siguiente start code con memchr
        size_t next_code_len = 0;
        size_t nalu_payload_end = find_next_start_code(src, nalu_payload_start, src_size, &next_code_len);

        size_t nalu_len = nalu_payload_end - nalu_payload_start;
        if (nalu_len == 0) {
            read_idx = nalu_payload_end;
            continue;
        }

        // Tipo de NAL unit en HEVC: (primer byte >> 1) & 0x3F
        uint8_t nal_type = (src[nalu_payload_start] >> 1) & 0x3F;

        // 1. Descartar NAL 63 (FEL - Full Enhancement Layer)
        if (nal_type == NAL_UNIT_UNSPEC63) {
            fel_dropped_count_++;
            read_idx = nalu_payload_end;
            continue;
        }

        // 2. Interceptar y transformar NAL 62 (Dolby Vision RPU) a Perfil 8.1
        if (nal_type == NAL_UNIT_UNSPEC62) {
            DoviResult result = dovi_transmute_p7_to_p81(
                &src[nalu_payload_start],
                nalu_len,
                rpu_scratch_buf_,
                sizeof(rpu_scratch_buf_)
            );

            if (result.success && result.out_size > 0) {
                // Escribir Start Code canónico de 4 bytes antes de la RPU transmutada
                if (write_idx + 4 + result.out_size <= dst_capacity) {
                    dst[write_idx++] = 0x00;
                    dst[write_idx++] = 0x00;
                    dst[write_idx++] = 0x00;
                    dst[write_idx++] = 0x01;
                    std::memcpy(&dst[write_idx], rpu_scratch_buf_, result.out_size);
                    write_idx += result.out_size;
                    rpu_converted_count_++;
                } else {
                    LOGW("Capacidad de buffer excedida al inyectar RPU P8.1");
                }
            } else {
                LOGW("Fallo en transmutación de RPU con libdovi. Pasando RPU original.");
                if (write_idx + start_code_len + nalu_len <= dst_capacity) {
                    std::memcpy(&dst[write_idx], &src[start_idx], start_code_len + nalu_len);
                    write_idx += (start_code_len + nalu_len);
                }
            }

            read_idx = nalu_payload_end;
            continue;
        }

        // 3. Paquete regular (Base Layer VCL, VPS, SPS, PPS, SEI) -> Copiar directamente
        size_t total_nalu_bytes = start_code_len + nalu_len;
        if (write_idx + total_nalu_bytes <= dst_capacity) {
            std::memcpy(&dst[write_idx], &src[start_idx], total_nalu_bytes);
            write_idx += total_nalu_bytes;
        } else {
            LOGW("Buffer de MediaCodec lleno al procesar NALU %u (bytes %zu)", nal_type, total_nalu_bytes);
            break;
        }

        read_idx = nalu_payload_end;
    }

    return write_idx;
}

bool HevcNaluParser::extract_csd0(
    const uint8_t* src,
    size_t src_size,
    std::vector<uint8_t>& out_csd0
) {
    if (!src || src_size == 0) return false;

    out_csd0.clear();
    size_t read_idx = 0;

    while (read_idx + 3 <= src_size) {
        size_t start_code_len = 0;
        size_t start_idx = find_next_start_code(src, read_idx, src_size, &start_code_len);
        if (start_idx >= src_size || start_code_len == 0) {
            break;
        }

        size_t nalu_start = start_idx + start_code_len;
        if (nalu_start >= src_size) {
            break;
        }

        size_t next_code_len = 0;
        size_t nalu_end = find_next_start_code(src, nalu_start, src_size, &next_code_len);

        uint8_t nal_type = (src[nalu_start] >> 1) & 0x3F;
        // Acumular VPS (32), SPS (33) y PPS (34) para el bloque CSD-0
        if (nal_type == NAL_UNIT_VPS || nal_type == NAL_UNIT_SPS || nal_type == NAL_UNIT_PPS) {
            size_t nalu_bytes = nalu_end - start_idx;
            out_csd0.insert(out_csd0.end(), &src[start_idx], &src[start_idx + nalu_bytes]);
        }

        read_idx = nalu_end;
    }

    return !out_csd0.empty();
}

size_t HevcNaluParser::process_frame_in_place(uint8_t* data, size_t size) {
    if (!data || size < 4) return size;

    size_t read_idx = 0;
    size_t write_idx = 0;
    size_t dropped_fel = 0;

    while (read_idx + 3 <= size) {
        size_t start_code_len = 0;
        size_t start_idx = find_next_start_code(data, read_idx, size, &start_code_len);
        if (start_idx >= size || start_code_len == 0) {
            break;
        }

        size_t nalu_payload_start = start_idx + start_code_len;
        if (nalu_payload_start >= size) {
            break;
        }

        size_t next_code_len = 0;
        size_t nalu_payload_end = find_next_start_code(data, nalu_payload_start, size, &next_code_len);

        uint8_t nal_type = (data[nalu_payload_start] >> 1) & 0x3F;

        // Descartar NAL 63 (FEL - Full Enhancement Layer)
        if (nal_type == NAL_UNIT_UNSPEC63) {
            dropped_fel++;
            read_idx = nalu_payload_end;
            continue;
        }

        // Mantener NAL válido (Base Layer, RPU NAL 62, SEI, etc.)
        size_t total_nalu_bytes = (nalu_payload_end - start_idx);
        if (write_idx != start_idx) {
            std::memmove(&data[write_idx], &data[start_idx], total_nalu_bytes);
        }
        write_idx += total_nalu_bytes;
        read_idx = nalu_payload_end;
    }

    if (dropped_fel > 0) {
        fel_dropped_count_ += dropped_fel;
        return write_idx;
    }

    return size;
}
