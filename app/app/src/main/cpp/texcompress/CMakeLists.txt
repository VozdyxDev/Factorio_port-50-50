cmake_minimum_required(VERSION 3.22.1)

project(TexCompress C)

# CPU heavy (palette search, k-means, dithering): full optimisation
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")

add_library(texcompress SHARED
            src/texcompress.c
            src/texcompress_jni.c)

# zlib is part of the NDK sysroot
target_link_libraries(texcompress z)
