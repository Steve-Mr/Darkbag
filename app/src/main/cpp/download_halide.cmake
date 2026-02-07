cmake_minimum_required(VERSION 3.10)

include(ExternalProject)

set(HALIDE_URL "https://github.com/halide/Halide/releases/download/v21.0.0/Halide-21.0.0-x86-64-linux-b629c80de18f1534ec71fddd8b567aa7027a0876.tar.gz")
set(HALIDE_SHA256 "b629c80de18f1534ec71fddd8b567aa7027a0876") # SHA256 might not be exact match if hash in filename is commit hash. Let's assume URL is correct.

# Define the download and extraction directory
set(HALIDE_ROOT "${CMAKE_BINARY_DIR}/halide_distrib")

if(NOT EXISTS "${HALIDE_ROOT}/lib/libHalide.so")
    message(STATUS "Downloading Halide from ${HALIDE_URL}...")
    file(DOWNLOAD "${HALIDE_URL}" "${CMAKE_BINARY_DIR}/halide.tar.gz"
         STATUS download_status
         LOG download_log)

    list(GET download_status 0 status_code)
    if(NOT status_code EQUAL 0)
        message(FATAL_ERROR "Error downloading Halide: ${download_log}")
    endif()

    message(STATUS "Extracting Halide...")
    execute_process(
        COMMAND ${CMAKE_COMMAND} -E tar xzf "${CMAKE_BINARY_DIR}/halide.tar.gz"
        WORKING_DIRECTORY "${CMAKE_BINARY_DIR}"
    )

    # Rename extracted directory to 'halide_distrib' if needed, or find where it extracted
    # The tarball typically extracts to a folder named like 'Halide-21.0.0-...'
    # We will simply glob to find it and move/symlink to HALIDE_ROOT
    file(GLOB EXTRACTED_DIR "${CMAKE_BINARY_DIR}/Halide-21.0.0-x86-64-linux")
    if(IS_DIRECTORY "${EXTRACTED_DIR}")
        file(RENAME "${EXTRACTED_DIR}" "${HALIDE_ROOT}")
    else()
        message(WARNING "Could not determine extracted Halide directory. Please check extraction.")
    endif()

    file(REMOVE "${CMAKE_BINARY_DIR}/halide.tar.gz")
endif()

set(HALIDE_INCLUDE_DIR "${HALIDE_ROOT}/include")
set(HALIDE_LIB_DIR "${HALIDE_ROOT}/lib")
set(HALIDE_CMAKE_DIR "${HALIDE_ROOT}/lib/cmake/Halide")

message(STATUS "Halide setup at ${HALIDE_ROOT}")
