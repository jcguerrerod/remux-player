#ifndef DOVI_FFI_H
#define DOVI_FFI_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct DoviResult {
    bool success;
    size_t out_size;
} DoviResult;

/**
 * Transmuta en tiempo real una NAL unit 62 (Dolby Vision RPU) de Perfil 7 (FEL/MEL)
 * a Perfil 8.1 (dvhe.08), eliminando metadatos EL (FEL) y adaptando las cabeceras
 * y mapeos DMv1/DMv2 para compatibilidad HDR10.
 *
 * @param input_ptr Puntero a la carga útil o NALU 62 (con o sin byte de cabecera HEVC).
 * @param input_size Longitud en bytes de los datos de entrada.
 * @param output_ptr Buffer preasignado donde se escribirá la NALU 62 P8.1 resultante.
 * @param output_capacity Tamaño máximo en bytes del buffer preasignado (recomendado >= 4096 bytes).
 * @return DoviResult con success=true y out_size igual al número de bytes válidos escritos.
 */
DoviResult dovi_transmute_p7_to_p81(
    const uint8_t* input_ptr,
    size_t input_size,
    uint8_t* output_ptr,
    size_t output_capacity
);

/**
 * Verifica si los datos corresponden a una RPU válida de Dolby Vision.
 */
bool dovi_is_valid_rpu(const uint8_t* input_ptr, size_t input_size);

#ifdef __cplusplus
}
#endif

#endif // DOVI_FFI_H
