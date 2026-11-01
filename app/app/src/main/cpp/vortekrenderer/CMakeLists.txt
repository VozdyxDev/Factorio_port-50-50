cmake_minimum_required(VERSION 3.22.1)

project(VortekRenderer)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -flto=thin -Wno-unused-function -Wimplicit-function-declaration -DVK_USE_PLATFORM_ANDROID_KHR")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto=thin")

# Debug output of the Vortek server: 0 = off, 1 = log every request
set(VORTEK_DEBUG_MODE 0 CACHE STRING "Vortek server debug mode (0 = off)")
add_compile_definitions(DEBUG_MODE=${VORTEK_DEBUG_MODE})

include_directories(include)
include_directories(../legacy_common/include)
include_directories(../libadrenotools/include)

add_library(vortekrenderer SHARED
            src/main.c
            src/vk_object.c
            src/request_handler.c
            src/bcn_layer.c
            src/vortek_report.c
            src/vulkan_helper.c
            src/vk_context.c
            src/resource_memory.c
            src/xwindow_swapchain.c
            src/texture_decoder.c
            src/shader_inspector.c
            src/async_pipeline_creator.c
            src/timeline_semaphore.c)

target_link_libraries(vortekrenderer
                      adrenotools
                      winlator_legacy
                      log
                      android)