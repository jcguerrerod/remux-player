#ifndef HEVC_NALU_PARSER_H
#define HEVC_NALU_PARSER_H

#include <cstdint>
#include <cstddef>
#include <vector>
#include "dovi_ffi.h"

class HevcNaluParser {
public:
    static constexpr uint8_t NAL_UNIT_TRAIL_N    = 0;
    static constexpr uint8_t NAL_UNIT_TRAIL_R    = 1;
    static constexpr uint8_t NAL_UNIT_IDR_W_RADL = 19;
    static constexpr uint8_t NAL_UNIT_IDR_N_LP   = 20;
    static constexpr uint8_t NAL_UNIT_CRA_NUT    = 21;
    static constexpr uint8_t NAL_UNIT_VPS        = 32;
    static constexpr uint8_t NAL_UNIT_SPS        = 33;
    static constexpr uint8_t NAL_UNIT_PPS        = 34;
    static constexpr uint8_t NAL_UNIT_AUD        = 35;
    static constexpr uint8_t NAL_UNIT_PREFIX_SEI = 39;
    static constexpr uint8_t NAL_UNIT_SUFFIX_SEI = 40;
    static constexpr uint8_t NAL_UNIT_UNSPEC62   = 62; // Dolby Vision RPU
    static constexpr uint8_t NAL_UNIT_UNSPEC63   = 63; // Dolby Vision FEL / MEL

    HevcNaluParser();
    ~HevcNaluParser() = default;

    /**
     * Procesa un paquete HEVC en formato Annex B:
     * - Descarta totalmente los NAL units de tipo 63 (FEL).
     * - Intercepta NAL units de tipo 62 (RPU), los convierte a Perfil 8.1 vía libdovi.
     * - Escribe el resultado directamente en el búfer de destino proporcionado por MediaCodec.
     *
     * @param src Puntero a los datos crudos del paquete (Annex B).
     * @param src_size Tamaño de los datos de entrada.
     * @param dst Búfer de destino mapeado en hardware (MediaCodec input buffer).
     * @param dst_capacity Capacidad máxima del búfer de destino.
     * @return Número de bytes útiles escritos en dst.
     */
    size_t process_frame_zero_copy(
        const uint8_t* __restrict src,
        size_t src_size,
        uint8_t* __restrict dst,
        size_t dst_capacity
    );

    /**
     * Procesa un búfer Annex B in-place descartando NAL 63 (FEL).
     * @param data Puntero directo al búfer en memoria física / compartida.
     * @param size Tamaño actual del payload en bytes.
     * @return Nuevo tamaño del payload tras filtrar NAL 63.
     */
    size_t process_frame_in_place(uint8_t* data, size_t size);

    /**
     * Extrae CSD-0 (VPS + SPS + PPS) del stream para configuración inicial del decodificador.
     */
    bool extract_csd0(
        const uint8_t* src,
        size_t src_size,
        std::vector<uint8_t>& out_csd0
    );

private:
    uint8_t rpu_scratch_buf_[4096];
    size_t rpu_converted_count_ = 0;
    size_t fel_dropped_count_ = 0;
};

#endif // HEVC_NALU_PARSER_H
