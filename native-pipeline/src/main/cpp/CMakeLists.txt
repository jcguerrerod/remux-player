cmake_minimum_required(VERSION 3.22.1)
project("native_pipeline")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libdovi-ffi/include
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET "i686-linux-android")
endif()

set(RUST_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../libdovi-ffi/target/${RUST_TARGET}/release")

if(EXISTS "${RUST_LIB_DIR}/libdovi_ffi.a")
    add_library(dovi_ffi STATIC IMPORTED)
    set_target_properties(dovi_ffi PROPERTIES
        IMPORTED_LOCATION "${RUST_LIB_DIR}/libdovi_ffi.a"
    )
else()
    add_library(dovi_ffi STATIC
        fallback/dovi_ffi_stub.cpp
    )
endif()

set(SOURCES
    jni/jni_bridge.cpp
    video/hevc_nalu_parser.cpp
    video/mediacodec_sink.cpp
    demuxer/matroska_demuxer.cpp
    subtitles/ass_renderer.cpp
)

add_library(native_pipeline SHARED ${SOURCES})

find_library(MEDIA_NDK mediandk)
find_library(GLES3_LIB GLESv3)
find_library(EGL_LIB EGL)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(native_pipeline
    dovi_ffi
    ${MEDIA_NDK}
    ${GLES3_LIB}
    ${EGL_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
)
