# On-device food photo recognition for iOS (FEAT-069): Android's engine
# (androidApp/src/main/cpp/vision/food_vision.cpp, unchanged) over the same
# pinned llama.cpp + libmtmd, plus the C bridge Swift calls. Built as one
# static library per platform by iosApp/scripts/build-vision.sh.
#
# CPU only, as on Android: the engine loads with n_gpu_layers = 0 and
# use_gpu = false, so Metal would be dead weight. The CPU code targets
# ARMv8.2 with dot product and FP16 arithmetic; the app only loads a model
# after checking both features (hw.optional.arm.FEAT_DotProd / FEAT_FP16).

cmake_minimum_required(VERSION 3.21)
project(cruxvision C CXX)

include(FetchContent)

set(CRUX_LLAMA_CPP_TAG b11396)
FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${CRUX_LLAMA_CPP_TAG}.tar.gz
    URL_HASH SHA256=fce45a07b2a6a0fb11f2b0fc8695ae5b463e95b47febdea3ae9f7db95f6c1f72
)

set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

set(ENGINE ${CMAKE_CURRENT_SOURCE_DIR}/../../androidApp/src/main/cpp/vision)
add_library(CruxVisionBridge STATIC
    ${ENGINE}/food_vision.cpp
    food_vision_c.cpp
)
target_include_directories(CruxVisionBridge PRIVATE ${ENGINE} ${CMAKE_CURRENT_SOURCE_DIR}/include/CruxVision)
target_link_libraries(CruxVisionBridge PRIVATE mtmd llama ggml)
target_compile_features(CruxVisionBridge PRIVATE cxx_std_17)
