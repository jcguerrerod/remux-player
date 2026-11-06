import sys
import os
import struct

def probe_mkv(file_path):
    print(f"=== Analizando archivo Remux: {file_path} ===")
    if not os.path.exists(file_path):
        print("El archivo no existe.")
        return

    size_gb = os.path.getsize(file_path) / (1024**3)
    print(f"Tamano en disco: {size_gb:.2f} GB")

    # Leer primeros 64MB para buscar NALUs HEVC
    with open(file_path, "rb") as f:
        chunk = f.read(64 * 1024 * 1024)

    # Buscar codigos de inicio Annex B 0x000001
    nal_counts = {}
    i = 0
    found_dovi_rpu = 0
    found_dovi_fel = 0

    while i + 4 < len(chunk):
        if chunk[i] == 0 and chunk[i+1] == 0 and chunk[i+2] == 1:
            byte0 = chunk[i+3]
            nal_type = (byte0 >> 1) & 0x3F
            nal_counts[nal_type] = nal_counts.get(nal_type, 0) + 1
            if nal_type == 62:
                found_dovi_rpu += 1
            elif nal_type == 63:
                found_dovi_fel += 1
            i += 4
        elif chunk[i] == 0 and chunk[i+1] == 0 and chunk[i+2] == 0 and chunk[i+3] == 1:
            byte0 = chunk[i+4]
            nal_type = (byte0 >> 1) & 0x3F
            nal_counts[nal_type] = nal_counts.get(nal_type, 0) + 1
            if nal_type == 62:
                found_dovi_rpu += 1
            elif nal_type == 63:
                found_dovi_fel += 1
            i += 5
        else:
            i += 1

    print(f"NAL units encontrados en los primeros 64MB:")
    for nt in sorted(nal_counts.keys()):
        name = "UNKNOWN"
        if nt == 32: name = "VPS"
        elif nt == 33: name = "SPS"
        elif nt == 34: name = "PPS"
        elif nt == 39: name = "PREFIX_SEI"
        elif nt == 40: name = "SUFFIX_SEI"
        elif nt == 62: name = "DOLBY_VISION_RPU (UNSPEC62)"
        elif nt == 63: name = "DOLBY_VISION_FEL_MEL (UNSPEC63)"
        elif nt in (19, 20): name = "IDR_SLICE"
        elif nt in (0, 1): name = "TRAIL_SLICE"
        print(f"  NAL {nt:2d} ({name:30s}): {nal_counts[nt]} unidades")

    print("\n--- Diagnostico Dolby Vision ---")
    if found_dovi_rpu > 0:
        print(f"  [OK] NAL 62 (Dolby Vision RPU) detectado ({found_dovi_rpu} instancias).")
    else:
        print("  [INFO] NAL 62 no esta en los primeros 64MB o empaquetado en longitud MP4/AVCC.")

    if found_dovi_fel > 0:
        print(f"  [ALERTA] NAL 63 (FEL/MEL) presente ({found_dovi_fel} instancias). Sera descartado por el pipeline.")
    else:
        print("  [INFO] NAL 63 no detectado en este segmento (Posible Profile 8 o MEL empaquetado).")

if __name__ == "__main__":
    remux_path = r"D:\Jellyfin Library\Movies\The Nightmare Before Christmas (1993).mkv"
    probe_mkv(remux_path)
