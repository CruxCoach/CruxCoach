# Desktop build of the app's food photo engine (FEAT-069) for prompt work.
#
#   cmake -S tools/food-vision -B build/food-vision -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/food-vision -j
#
# Uses the same pinned llama.cpp archive as the Android build.
cmake_minimum_required(VERSION 3.18)
project(food-vision-cli C CXX)

include(FetchContent)
FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11396.tar.gz
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
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llama_cpp)

set(ENGINE ${CMAKE_CURRENT_SOURCE_DIR}/../../androidApp/src/main/cpp/vision)
add_executable(food_vision_cli food_vision_cli.cpp ${ENGINE}/food_vision.cpp)
target_include_directories(food_vision_cli PRIVATE ${ENGINE} ${llama_cpp_SOURCE_DIR}/vendor/stb)
target_link_libraries(food_vision_cli PRIVATE mtmd llama ggml)
target_compile_features(food_vision_cli PRIVATE cxx_std_17)
