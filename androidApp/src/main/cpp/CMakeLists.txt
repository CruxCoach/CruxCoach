cmake_minimum_required(VERSION 3.18)
project(cruxcoach-zstd C)

# Collect zstd decompression source files (no compression needed)
file(GLOB ZSTD_COMMON_SOURCES "zstd/common/*.c")
file(GLOB ZSTD_DECOMPRESS_SOURCES "zstd/decompress/*.c")

add_library(cruxcoach-zstd SHARED
    zstd_jni.c
    ${ZSTD_COMMON_SOURCES}
    ${ZSTD_DECOMPRESS_SOURCES}
)

target_include_directories(cruxcoach-zstd PRIVATE zstd zstd/common)

# Only decompression — disable compression to reduce binary size
target_compile_definitions(cruxcoach-zstd PRIVATE
    ZSTD_DISABLE_COMPRESS=1
)

target_link_libraries(cruxcoach-zstd log)
