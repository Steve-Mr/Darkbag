cmake_minimum_required(VERSION 3.10.2)

project("cameraxbasic")

add_library(
        native-lib
        SHARED
        native-lib.cpp)

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
        GLESv3
        EGL
        -fopenmp
        -static-openmp)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
