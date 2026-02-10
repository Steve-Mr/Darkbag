cmake_minimum_required(VERSION 3.10.2)

project("cameraxbasic")

# LibRaw Configuration
set(CMAKE_CXX_STANDARD 17)
add_definitions(-DNO_LCMS -DNO_JASPER -DNO_JPEG)

# Setup Halide (Host)
include(download_halide.cmake)
include_directories(${HALIDE_INCLUDE_DIR})

# --- LibTIFF (FetchContent) ---
include(FetchContent)

# Disable LibTIFF optional dependencies/tools to keep build minimal
set(tiff-tools OFF CACHE BOOL "" FORCE)
set(tiff-tests OFF CACHE BOOL "" FORCE)
set(tiff-contrib OFF CACHE BOOL "" FORCE)
set(tiff-docs OFF CACHE BOOL "" FORCE)
set(tiff-install OFF CACHE BOOL "" FORCE)
set(tiff-opengl OFF CACHE BOOL "" FORCE)
set(ld-version-script OFF CACHE BOOL "" FORCE)
set(cxx OFF CACHE BOOL "" FORCE)

# Disable external dependencies finding
set(CMAKE_DISABLE_FIND_PACKAGE_JPEG ON)
set(CMAKE_DISABLE_FIND_PACKAGE_JBIG ON)
set(CMAKE_DISABLE_FIND_PACKAGE_LERC ON)
set(CMAKE_DISABLE_FIND_PACKAGE_LZMA ON)
set(CMAKE_DISABLE_FIND_PACKAGE_WebP ON)
set(CMAKE_DISABLE_FIND_PACKAGE_ZSTD ON)

FetchContent_Declare(
  libtiff
  GIT_REPOSITORY https://gitlab.com/libtiff/libtiff.git
  GIT_TAG        v4.6.0
)

FetchContent_MakeAvailable(libtiff)
include_directories(${tiff_SOURCE_DIR}/libtiff ${tiff_BINARY_DIR}/libtiff)

# --- HDR+ Generator (Host Tool) ---
set(HDRPLUS_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/hdr-plus/src")
set(HDRPLUS_GENERATOR_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/hdrplus_pipeline_generator.cpp"
    "${HDRPLUS_SRC_DIR}/align.cpp"
    "${HDRPLUS_SRC_DIR}/merge.cpp"
    "${HDRPLUS_SRC_DIR}/finish.cpp"
    "${HDRPLUS_SRC_DIR}/util.cpp"
)

set(GENERATOR_EXEC "${CMAKE_BINARY_DIR}/hdrplus_generator")
set(GENERATOR_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY ${GENERATOR_OUTPUT_DIR})

add_custom_command(
    OUTPUT "${GENERATOR_EXEC}"
    COMMAND c++ -std=c++17 ${HDRPLUS_GENERATOR_SRC}
            -I"${HDRPLUS_SRC_DIR}"
            -I"${HALIDE_INCLUDE_DIR}"
            -L"${HALIDE_LIB_DIR}"
            -lHalide -lHalide_GenGen -lpthread -ldl -lz
            -Wl,-rpath,"${HALIDE_LIB_DIR}"
            -o "${GENERATOR_EXEC}"
    DEPENDS ${HDRPLUS_GENERATOR_SRC}
    COMMENT "Building Halide Generator (Host)"
)

# --- Generate CPU Pipeline ---
set(HALIDE_TARGET_CPU "arm-64-android-no_runtime")
set(GENERATED_HEADER_CPU "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline_cpu.h")
set(GENERATED_LIB_CPU "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline_cpu.a")

add_custom_command(
    OUTPUT "${GENERATED_HEADER_CPU}" "${GENERATED_LIB_CPU}"
    COMMAND "${GENERATOR_EXEC}"
            -g hdrplus_raw_pipeline
            -f hdrplus_raw_pipeline_cpu
            -e static_library,h
            -o "${GENERATOR_OUTPUT_DIR}"
            target=${HALIDE_TARGET_CPU}
    DEPENDS "${GENERATOR_EXEC}"
    COMMENT "Running Halide Generator for CPU"
)

# --- Generate GPU Pipeline (OpenCL) ---
set(HALIDE_TARGET_OPENCL "arm-64-android-opencl-no_runtime")
set(GENERATED_HEADER_OPENCL "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline_opencl.h")
set(GENERATED_LIB_OPENCL "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline_opencl.a")

add_custom_command(
    OUTPUT "${GENERATED_HEADER_OPENCL}" "${GENERATED_LIB_OPENCL}"
    COMMAND "${GENERATOR_EXEC}"
            -g hdrplus_raw_pipeline
            -f hdrplus_raw_pipeline_opencl
            -e static_library,h
            -o "${GENERATOR_OUTPUT_DIR}"
            target=${HALIDE_TARGET_OPENCL}
    DEPENDS "${GENERATOR_EXEC}"
    COMMENT "Running Halide Generator for GPU (OpenCL)"
)

# --- Generate GPU Pipeline (Vulkan) ---
set(HALIDE_TARGET_VULKAN "arm-64-android-vulkan-vk_int16-no_runtime")
set(GENERATED_HEADER_VULKAN "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline_vulkan.h")
set(GENERATED_LIB_VULKAN "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline_vulkan.a")

add_custom_command(
    OUTPUT "${GENERATED_HEADER_VULKAN}" "${GENERATED_LIB_VULKAN}"
    COMMAND "${GENERATOR_EXEC}"
            -g hdrplus_raw_pipeline
            -f hdrplus_raw_pipeline_vulkan
            -e static_library,h
            -o "${GENERATOR_OUTPUT_DIR}"
            target=${HALIDE_TARGET_VULKAN}
    DEPENDS "${GENERATOR_EXEC}"
    COMMENT "Running Halide Generator for GPU (Vulkan)"
)

# --- Generate Standalone Runtime ---
set(HALIDE_TARGET_RUNTIME "arm-64-android-opencl-vulkan")
set(GENERATED_RUNTIME "${GENERATOR_OUTPUT_DIR}/halide_runtime.a")

add_custom_command(
    OUTPUT "${GENERATED_RUNTIME}"
    COMMAND "${GENERATOR_EXEC}"
            -r halide_runtime
            -e static_library
            -o "${GENERATOR_OUTPUT_DIR}"
            target=${HALIDE_TARGET_RUNTIME}
    DEPENDS "${GENERATOR_EXEC}"
    COMMENT "Running Halide Generator for Runtime"
)

add_custom_target(generate_hdrplus_halide DEPENDS
    "${GENERATED_LIB_CPU}" "${GENERATED_HEADER_CPU}"
    "${GENERATED_LIB_OPENCL}" "${GENERATED_HEADER_OPENCL}"
    "${GENERATED_LIB_VULKAN}" "${GENERATED_HEADER_VULKAN}"
    "${GENERATED_RUNTIME}"
)

include_directories(${GENERATOR_OUTPUT_DIR})
include_directories(libraw)

file(GLOB_RECURSE LIBRAW_SOURCES "libraw/src/*.cpp")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

add_library(
        native-lib
        SHARED
        native-lib.cpp
        HdrPlusJNI.cpp
        ColorPipe.cpp
        ${LIBRAW_SOURCES}
        "${GENERATED_LIB_CPU}"
        "${GENERATED_LIB_OPENCL}"
        "${GENERATED_LIB_VULKAN}"
        "${GENERATED_RUNTIME}"
)

add_dependencies(native-lib generate_hdrplus_halide)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(
        native-lib
        "${GENERATED_LIB_CPU}"
        "${GENERATED_LIB_OPENCL}"
        "${GENERATED_LIB_VULKAN}"
        "${GENERATED_RUNTIME}"
        tiff
        ${log-lib}
        ${jnigraphics-lib}
        ${android-lib}
        -fopenmp
        -static-openmp
)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
