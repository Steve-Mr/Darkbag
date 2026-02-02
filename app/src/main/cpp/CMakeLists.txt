cmake_minimum_required(VERSION 3.10.2)

project("cameraxbasic")

# LibRaw Configuration
set(CMAKE_CXX_STANDARD 11)
add_definitions(-DNO_LCMS -DNO_JASPER -DNO_JPEG)

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
        ${LIBRAW_SOURCES})

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
        ${log-lib}
        ${jnigraphics-lib}
        ${android-lib}
        -fopenmp
        -static-openmp)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
