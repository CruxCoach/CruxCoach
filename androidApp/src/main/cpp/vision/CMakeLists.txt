# On-device food photo recognition (FEAT-069): llama.cpp + libmtmd, linked
# statically into one JNI library that only the ":vision" process loads.
#
# The CPU code targets ARMv8.2 with dot product and FP16 arithmetic. The app
# loads this library only after VisionCapability has confirmed both features
# (asimddp and asimdhp in /proc/cpuinfo), so older cores never execute it.

include(FetchContent)

set(CRUX_LLAMA_CPP_TAG b11396)
FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${CRUX_LLAMA_CPP_TAG}.tar.gz
    URL_HASH SHA256=fce45a07b2a6a0fb11f2b0fc8695ae5b463e95b47febdea3ae9f7db95f6c1f72
)

# Debug APKs build native code with -O0, which makes inference unusably slow.
# These directory-scoped flags apply to this library and to llama.cpp below.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

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
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

add_library(cruxcoach-vision SHARED
    food_vision.cpp
    food_vision_jni.cpp
)
target_compile_features(cruxcoach-vision PRIVATE cxx_std_17)
target_link_libraries(cruxcoach-vision PRIVATE mtmd llama ggml log)
# Keep only the JNI entry points visible.
set_target_properties(cruxcoach-vision PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(cruxcoach-vision PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
