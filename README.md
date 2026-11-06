# Android TV 4K UHD Remux Player (Dolby Vision P7 -> P8.1 Zero-Copy)

Reproductor multimedia nativo de ultra-alto rendimiento para Android TV, especializado en la reproducción **Direct Play** de archivos 4K Blu-ray Remux desde un servidor Jellyfin sin transcodificación.

## Arquitectura del Sistema

```
├── libdovi-ffi/            # Crate en Rust (C-ABI) para transmutación al vuelo de RPU DV
├── native-pipeline/        # C++20 Core NDK: Demuxer, NALU Parser, MediaCodec Sink, ASS Engine
├── core-media/             # Orquestador Kotlin: Bitstream AudioTrack Direct, Jellyfin REST Client
└── app/                    # UI Leanback para Android TV
```

### Características Técnicas Clave
1. **Conversión al Vuelo Dolby Vision Perfil 7 a Perfil 8.1 (Zero-Copy)**:
   - Descarte estricto de paquetes **NAL Unit 63** (`FEL - Full Enhancement Layer`) para prevenir cuellos de botella térmicos y de bus de memoria en Smart TVs con SoCs de 1.5 - 2 GB RAM.
   - Intercepción de **NAL Unit 62** (`Dolby Vision RPU`) y transmutación al vuelo a Perfil 8.1 (`dvhe.08`) mediante `libdovi` (escrita en Rust y vinculada estáticamente).
   - Inyección directa en el buffer físico mapeado de hardware de `AMediaCodec_dequeueInputBuffer` sin copias intermedias.
2. **Bandera Dolby Vision Nativa en Panel**:
   - Configuración explícita de `MediaFormat` (`video/dolby-vision`, perfil 32 `DolbyVisionProfileDvheSt`, color-standard BT.2020, color-transfer SMPTE ST 2084 / PQ).
3. **Pipeline de Audio HD Bitstream Directo**:
   - Salida `AudioTrack` en modo Direct / IEC 61937 (`FLAG_HW_AV_SYNC`, `ENCODING_DOLBY_TRUEHD`, `ENCODING_DTS_HD_MA`, `ENCODING_E_AC3_JOC`).
   - Verificación dinámica de soporte HDMI EDID contra receptores AVR / Soundbars.
4. **Motor de Subtítulos PGS Blu-ray (`libass` + OpenGL ES 3.0)**:
   - Renderizado tipográfico idéntico a subtítulos PGS comerciales de Blu-ray: fuente estilo *DejaVu Sans* / *Gotham*, relleno blanco opaco (`#FFFFFF`), contorno perimetral nítido de 2.5px, sombra perimetral de 1.5px y margen vertical de 65px (safe area para 2.39:1 y 16:9).
5. **Cliente Jellyfin Anti-Transcoding**:
   - Reporte forzado de `DeviceProfile` con `DirectPlay` para HEVC `dvhe.08` y códecs de audio HD, garantizando que el servidor Jellyfin transmita el archivo original vía HTTP sin tocar la CPU ni GPU.

---

## Compilación y Despliegue

### Requisitos Previos
- Android NDK r26 o superior.
- Rust toolchain con `cargo-ndk`:
  ```bash
  rustup target add aarch64-linux-android
  cargo install cargo-ndk
  ```
- CMake 3.22.1+

### 1. Compilación de la librería Rust `libdovi-ffi`
```bash
cd libdovi-ffi
cargo ndk -t arm64-v8a build --release
```
Esto generará el archivo `target/aarch64-linux-android/release/libdovi_ffi.a`.

### 2. Compilación del Proyecto Android
Desde la raíz del proyecto:
```bash
./gradlew assembleRelease
```
El APK resultante para Android TV se encontrará en `app/build/outputs/apk/release/app-release.apk`.
