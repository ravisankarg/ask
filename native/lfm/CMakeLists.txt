cmake_minimum_required(VERSION 3.22)
project(askgalaxy_lfm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# Android 15+ devices can use 16 KB kernel pages.  This must apply to every
# shared object produced by the llama.cpp subtree as well as our JNI bridge;
# applying it only to askgalaxy_lfm would leave its libllama/libggml/libmtmd
# dependencies unloadable on those devices.
set(CMAKE_SHARED_LINKER_FLAGS
    "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} llama-build)

add_library(askgalaxy_lfm SHARED lfm_kv_jni.cpp)
target_include_directories(askgalaxy_lfm PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(askgalaxy_lfm PRIVATE llama mtmd log android)
