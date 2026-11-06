#include "dovi_ffi.h"
#include <cstring>
#include <android/log.h>

#define TAG "DOVI_FFI_STUB"

extern "C" {

DoviResult dovi_transmute_p7_to_p81(
    const uint8_t* input_ptr,
    size_t input_size,
    uint8_t* output_ptr,
    size_t output_capacity
) {
    if (!input_ptr || !output_ptr || input_size == 0 || output_capacity < input_size) {
        return { false, 0 };
    }

    // Stub de contingencia: si la librería estática de Rust aún no fue generada,
    // emite aviso y copia los bytes preservando la ejecución del pipeline.
    __android_log_print(ANDROID_LOG_WARN, TAG, 
        "Aviso: Usando stub fallback de libdovi. Compile libdovi-ffi con cargo-ndk para transmute real.");

    std::memcpy(output_ptr, input_ptr, input_size);
    return { true, input_size };
}

bool dovi_is_valid_rpu(const uint8_t* input_ptr, size_t input_size) {
    return (input_ptr != nullptr && input_size > 4);
}

}
