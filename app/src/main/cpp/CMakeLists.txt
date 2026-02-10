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
# We need to compile the generator for the HOST machine (x86_64), not the target (Android).
# However, CMake inside Android Studio is cross-compiling.
# To cleanly handle this, we can define the generator sources here but must ensure
# they are built with the host compiler.
#
# A common trick in Android builds is to trust that the user (or a pre-step)
# has built the generator, or use a separate CMake instance.
# BUT, for simplicity in this integration, we will rely on AOT compilation
# invoked manually or via a custom command if we can ensure the host toolchain is available.
#
# Given the constraints, we will define the generator source and use a custom command
# that invokes 'c++' (system compiler) directly to build the generator.

set(HDRPLUS_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/hdr-plus/src")
set(HDRPLUS_GENERATOR_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/hdrplus_pipeline_generator.cpp"
    "${HDRPLUS_SRC_DIR}/align.cpp"
    "${HDRPLUS_SRC_DIR}/merge.cpp"
    "${HDRPLUS_SRC_DIR}/finish.cpp"
    "${HDRPLUS_SRC_DIR}/util.cpp"
    "${HALIDE_ROOT}/share/Halide/tools/GenGen.cpp"
)

set(GENERATOR_EXEC "${CMAKE_BINARY_DIR}/hdrplus_generator")

# Create a dummy target for the library to satisfy CMake's dependency graph
# BEFORE the custom command runs.
# Removed old target definition to use the consolidated one later

set(GENERATOR_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY ${GENERATOR_OUTPUT_DIR})

# We use a custom command to build the generator using the HOST compiler (/usr/bin/c++)
# We link against the downloaded Halide (Host) library.
# Note: We need to set RPATH or LD_LIBRARY_PATH for the generator to run.
#
# IMPORTANT: Halide v21 uses a new registration mechanism. We need to link against libHalide_GenGen.a
# or include GenGen.cpp manually. The distribution seems to provide libHalide_GenGen.a.
# However, `GenGen.cpp` is NOT in `share/Halide/tools/` in this specific tarball layout (based on `find` output).
#
# We will check if `libHalide_GenGen.a` exists in `lib/`. The `find` output shows:
# .../lib/libHalide_GenGen.a
#
# So we link against `Halide_GenGen` (which contains the main entry point for generators).
# We remove the manual `GenGen.cpp` inclusion from the source list and link the static lib instead.

list(REMOVE_ITEM HDRPLUS_GENERATOR_SRC "${HALIDE_ROOT}/share/Halide/tools/GenGen.cpp")

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

# Run the generator to produce the target object file
# We target arm-64-android with OpenCL and Vulkan support, plus profiling.
set(HALIDE_TARGET "arm-64-android-opencl-vulkan-vk_int16-profile")
set(GENERATED_HEADER "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline.h")
set(GENERATED_LIB "${GENERATOR_OUTPUT_DIR}/hdrplus_raw_pipeline.a") # Static lib output

add_custom_command(
    OUTPUT "${GENERATED_HEADER}" "${GENERATED_LIB}"
    COMMAND "${GENERATOR_EXEC}"
            -g hdrplus_raw_pipeline
            -f hdrplus_raw_pipeline
            -e static_library,h
            -o "${GENERATOR_OUTPUT_DIR}"
            target=${HALIDE_TARGET}
    DEPENDS "${GENERATOR_EXEC}"
    COMMENT "Running Halide Generator for ${HALIDE_TARGET}"
)

# Update dependency target
add_custom_target(generate_hdrplus_halide DEPENDS "${GENERATED_LIB}" "${GENERATED_HEADER}")

# Expose generated files to the build
include_directories(${GENERATOR_OUTPUT_DIR})
include_directories(libraw)

# Glob all sources
file(GLOB_RECURSE LIBRAW_SOURCES
    "libraw/src/*.cpp"
)

# Filter out placeholder/conflicting files
# *_ph.cpp files are usually "placeholders" or specific implementation alternatives that conflict with the standard ones
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

add_library(
        native-lib
        SHARED
        native-lib.cpp
        HdrPlusJNI.cpp
        ColorPipe.cpp
        ${LIBRAW_SOURCES}
        "${GENERATED_LIB}"
)

# Ensure the generation happens before native-lib compilation
add_dependencies(native-lib generate_hdrplus_halide)

find_library(
        log-lib
        log)

find_library(
        jnigraphics-lib
        jnigraphics)

find_library(
        android-lib
        android)

target_link_libraries(
        native-lib
        "${GENERATED_LIB}"
        tiff
        ${log-lib}
        ${jnigraphics-lib}
        ${android-lib}
        -fopenmp
        -static-openmp)


set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
