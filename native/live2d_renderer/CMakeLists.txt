cmake_minimum_required(VERSION 3.16)
project(Live2DRenderer CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(MSVC)
  add_compile_options(/utf-8)
endif()

# Official Cubism SDK for Native is proprietary and must NOT be redistributed.
# Point CUBISM_SDK_ROOT at your locally extracted CubismSdkForNative-5-r.5 tree.
if(NOT CUBISM_SDK_ROOT AND DEFINED ENV{CUBISM_SDK_ROOT})
  set(CUBISM_SDK_ROOT "$ENV{CUBISM_SDK_ROOT}")
endif()
if(NOT CUBISM_SDK_ROOT)
  message(FATAL_ERROR
    "CUBISM_SDK_ROOT is not set.\n"
    "Download Cubism 5 SDK for Native from Live2D, extract it, then pass:\n"
    "  cmake -DCUBISM_SDK_ROOT=\"C:/path/to/CubismSdkForNative-5-r.5\" ...\n"
    "or set the CUBISM_SDK_ROOT environment variable.")
endif()

get_filename_component(CUBISM_SDK_ROOT "${CUBISM_SDK_ROOT}" ABSOLUTE)
if(NOT EXISTS "${CUBISM_SDK_ROOT}/Core/include/Live2DCubismCore.h")
  message(FATAL_ERROR
    "CUBISM_SDK_ROOT does not look like CubismSdkForNative-5-r.5:\n"
    "  ${CUBISM_SDK_ROOT}\n"
    "Expected Core/include/Live2DCubismCore.h under that directory.")
endif()

set(CORE_PATH "${CUBISM_SDK_ROOT}/Core")
set(FRAMEWORK_PATH "${CUBISM_SDK_ROOT}/Framework")
set(THIRD_PARTY_PATH "${CUBISM_SDK_ROOT}/Samples/OpenGL/thirdParty")
set(GLEW_PATH "${THIRD_PARTY_PATH}/glew")
set(STB_PATH "${THIRD_PARTY_PATH}/stb")
set(FRAMEWORK_SHADER_PATH "${FRAMEWORK_PATH}/src/Rendering/OpenGL/Shaders/Standard")
set(CORE_LIB "${CORE_PATH}/lib/windows/x86_64/143/Live2DCubismCore_MD.lib")

foreach(required_path IN ITEMS
    "${CORE_LIB}"
    "${GLEW_PATH}/include/GL/glew.h"
    "${STB_PATH}/stb_image.h"
    "${FRAMEWORK_SHADER_PATH}/FragShaderSrc.frag")
  if(NOT EXISTS "${required_path}")
    message(FATAL_ERROR "Missing required Cubism SDK file: ${required_path}")
  endif()
endforeach()

message(STATUS "Using Cubism SDK at: ${CUBISM_SDK_ROOT}")

# GLEW (static)
set(BUILD_UTILS OFF CACHE BOOL "" FORCE)
add_subdirectory("${GLEW_PATH}/build/cmake" "${CMAKE_CURRENT_BINARY_DIR}/glew")

# Live2D Cubism Core (static import of official .lib; not redistributed by this repo)
add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore PROPERTIES
  IMPORTED_LOCATION "${CORE_LIB}"
  INTERFACE_INCLUDE_DIRECTORIES "${CORE_PATH}/include"
)

# Framework
set(FRAMEWORK_SOURCE OpenGL)
add_subdirectory("${FRAMEWORK_PATH}" "${CMAKE_CURRENT_BINARY_DIR}/Framework")
target_compile_definitions(Framework PUBLIC CSM_TARGET_WIN_GL GLEW_STATIC)
target_include_directories(Framework PUBLIC
  "${GLEW_PATH}/include"
  "${CORE_PATH}/include"
)
target_link_libraries(Framework Live2DCubismCore glew_s)

# DLL target
set(SOURCES
  src/live2d_renderer.cpp
  src/live2d_model.cpp
  src/live2d_pal.cpp
)

set(HEADERS
  include/live2d_renderer.h
  src/live2d_model.h
  src/live2d_pal.h
)

add_library(live2d_renderer SHARED ${SOURCES} ${HEADERS})

target_compile_definitions(live2d_renderer PRIVATE
  LIVE2D_RENDERER_EXPORTS
  CSM_TARGET_WIN_GL
  GLEW_STATIC
)

target_include_directories(live2d_renderer PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/include"
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${CORE_PATH}/include"
  "${FRAMEWORK_PATH}/src"
  "${GLEW_PATH}/include"
  "${STB_PATH}"
)

target_link_libraries(live2d_renderer PRIVATE
  Framework
  Live2DCubismCore
  glew_s
  opengl32
)

set_target_properties(live2d_renderer PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/bin"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/bin"
  ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/lib"
)

# Bundle official shaders next to the DLL for local smoke / manual deploy.
# Shaders remain proprietary Live2D assets and are never committed to this repository.
add_custom_command(
  TARGET live2d_renderer
  POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E make_directory
    "$<TARGET_FILE_DIR:live2d_renderer>/FrameworkShaders"
  COMMAND ${CMAKE_COMMAND} -E copy_directory
    "${FRAMEWORK_SHADER_PATH}"
    "$<TARGET_FILE_DIR:live2d_renderer>/FrameworkShaders"
)

# Optional offscreen init smoke test (no model assets required).
option(LIVE2D_RENDERER_BUILD_SMOKE "Build offscreen init smoke test" ON)
if(LIVE2D_RENDERER_BUILD_SMOKE)
  add_executable(live2d_render_smoke test_render.cpp)
  target_include_directories(live2d_render_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
  target_link_libraries(live2d_render_smoke PRIVATE live2d_renderer)
  set_target_properties(live2d_render_smoke PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/bin"
  )
endif()
