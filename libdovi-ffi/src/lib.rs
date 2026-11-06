use std::slice;
use dolby_vision::rpu::dovi_rpu::DoviRpu;
use dolby_vision::rpu::profiles::DoviProfile;

#[repr(C)]
pub struct DoviResult {
    pub success: bool,
    pub out_size: usize,
}

/// Transmuta un NALU 62 (RPU Perfil 7) a Perfil 8.1 en un buffer preasignado.
/// Sin asignaciones dinámicas fuera de la deserialización de la RPU.
#[no_mangle]
pub unsafe extern "C" fn dovi_transmute_p7_to_p81(
    input_ptr: *const u8,
    input_size: usize,
    output_ptr: *mut u8,
    output_capacity: usize,
) -> DoviResult {
    if input_ptr.is_null() || output_ptr.is_null() || input_size == 0 || output_capacity == 0 {
        return DoviResult {
            success: false,
            out_size: 0,
        };
    }

    let raw_payload = slice::from_raw_parts(input_ptr, input_size);

    // Parsear el NALU UNSPEC62 o RPU directa
    // Si contiene la cabecera HEVC NALU de 2 bytes, parse_unspec62_nalu se encarga de des-escapar
    let parsed_rpu = match DoviRpu::parse_unspec62_nalu(raw_payload) {
        Ok(rpu) => Ok(rpu),
        Err(_) => DoviRpu::parse_rpu(raw_payload),
    };

    match parsed_rpu {
        Ok(mut rpu) => {
            // Modo 1: Convertir P7 a P8.1 conservando metadatos dinámicos L1..L11
            // y eliminando dependencias de Enhancement Layer (FEL).
            if rpu.convert_with_mode(DoviProfile::Profile8, 1).is_err() {
                return DoviResult {
                    success: false,
                    out_size: 0,
                };
            }

            // Serializar el NALU 62 resultante conforme a la especificación HEVC Annex B
            match rpu.write_hevc_unspec62_nalu() {
                Ok(converted_nalu) => {
                    let out_len = converted_nalu.len();
                    if out_len > output_capacity {
                        return DoviResult {
                            success: false,
                            out_size: 0,
                        };
                    }

                    std::ptr::copy_nonoverlapping(converted_nalu.as_ptr(), output_ptr, out_len);
                    DoviResult {
                        success: true,
                        out_size: out_len,
                    }
                }
                Err(_) => DoviResult {
                    success: false,
                    out_size: 0,
                },
            }
        }
        Err(_) => DoviResult {
            success: false,
            out_size: 0,
        },
    }
}

/// Validación rápida de RPU para el demuxer
#[no_mangle]
pub unsafe extern "C" fn dovi_is_valid_rpu(input_ptr: *const u8, input_size: usize) -> bool {
    if input_ptr.is_null() || input_size == 0 {
        return false;
    }
    let raw_payload = slice::from_raw_parts(input_ptr, input_size);
    DoviRpu::parse_unspec62_nalu(raw_payload).is_ok() || DoviRpu::parse_rpu(raw_payload).is_ok()
}
