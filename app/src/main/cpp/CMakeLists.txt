cmake_minimum_required(VERSION 3.22.1)
project("aimobile" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Les bibliothèques statiques de llama.cpp sont liées dans une .so : PIC obligatoire.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --------------------------------------------------------------------------
# llama.cpp
# Version figée pour des builds reproductibles. Pour la changer, modifier
# LLAMA_CPP_REF (un commit ou un tag de github.com/ggml-org/llama.cpp).
# Pour compiler avec une copie locale : -DLLAMA_CPP_DIR=/chemin/vers/llama.cpp
# --------------------------------------------------------------------------
set(LLAMA_CPP_REF "0c1e57098" CACHE STRING "Commit ou tag de llama.cpp")
set(LLAMA_CPP_DIR "" CACHE PATH "Copie locale de llama.cpp (optionnel)")

set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Snapdragon 888 (Cortex-X1 / A78) : dotprod + fp16, pas de i8mm.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

if(LLAMA_CPP_DIR)
    add_subdirectory(${LLAMA_CPP_DIR} build-llama)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/${LLAMA_CPP_REF}.tar.gz
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

# --------------------------------------------------------------------------
# Pont JNI
# --------------------------------------------------------------------------
add_library(aimobile SHARED llama_jni.cpp)
target_compile_definitions(aimobile PRIVATE "LLAMA_CPP_REF=\"${LLAMA_CPP_REF}\"")

if(ANDROID)
    target_link_libraries(aimobile llama llama-common ggml-cpu android log)
else()
    # Build hôte (vérification de compilation hors Android).
    find_package(JNI REQUIRED)
    target_include_directories(aimobile PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(aimobile llama llama-common)
endif()
