cmake_minimum_required(VERSION 3.15)
project(FastVAD CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS} ${CMAKE_CURRENT_SOURCE_DIR}/src)

set(SOURCES
    src/fastvad_native.cpp
)

add_library(fastvad SHARED ${SOURCES})

set_target_properties(fastvad PROPERTIES
    PREFIX ""
    OUTPUT_NAME "fastvad"
)

target_link_libraries(fastvad ${JNI_LIBRARIES})

if(WIN32)
    target_compile_definitions(fastvad PRIVATE _CRT_SECURE_NO_WARNINGS)
endif()