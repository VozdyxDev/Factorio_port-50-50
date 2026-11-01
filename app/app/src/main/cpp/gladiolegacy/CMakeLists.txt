cmake_minimum_required(VERSION 3.22.1)

project(GladioLegacy)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -flto=thin -Wno-unused-function -Wimplicit-function-declaration -Werror=incompatible-pointer-types")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto=thin")

# Debug output of the Gladio server: 0 = off, otherwise a mask of DEBUG_MODE_* from include/gladio.h
set(GLADIO_DEBUG_MODE 0 CACHE STRING "Gladio server debug mask (0 = off)")
add_compile_definitions(DEBUG_MODE=${GLADIO_DEBUG_MODE})

include_directories(include)
include_directories(../legacy_common/include)
include_directories(../vortekrenderer/include)

add_library(gladiolegacy SHARED
            src/main.c
            src/gl_context.c
            src/gl_renderer.c
            src/request_handler.c
            src/shader_material.c
            src/shader_converter.c
            src/gl_formats.c
            src/gl_texture.c
            src/attrib_stack.c
            src/gl_query.c
            src/compressed_texture.c
            src/arb_program.c
            src/gl_buffer.c
            src/gl_vao.c
            src/gl_framebuffer.c)

target_link_libraries(gladiolegacy
                      winlator_legacy
                      winlator
                      log
                      android
                      EGL
                      GLESv2
                      GLESv3)