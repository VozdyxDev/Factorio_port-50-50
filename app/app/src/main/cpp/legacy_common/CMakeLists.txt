cmake_minimum_required(VERSION 3.22.1)

project(WinlatorLegacy)

# Private copy of the helper code (arrays, ring buffer, ashmem) of the original Winlator.
# Used ONLY by vortekrenderer and gladiolegacy. It is built as a static library with hidden symbols, so it can never
# clash with the (modified) libwinlator.so of this app that the Turnip / Zink path uses.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -flto=thin -Wno-unused-function -Wimplicit-function-declaration -fvisibility=hidden -fPIC")

include_directories(include)

add_library(winlator_legacy STATIC
            src/arrays.c
            src/ring_buffer.c
            src/sysvshared_memory.c
            src/hwbuffer.c)

target_link_libraries(winlator_legacy log android)
