cmake_minimum_required(VERSION 3.16)

# Match the historically validated live2d_renderer.dll on Windows: static MSVC CRT (/MT),
# so the DLL only depends on system OpenGL/USER32/GDI32 — no VC++ redistributable.
# Must be set before project() / add_subdirectory so Framework & GLEW inherit it.
if(MSVC)
  if(POLICY CMP0091)
    cmake_policy(SET CMP0091 NEW)
  endif()
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>" CACHE STRING "" FORCE)
endif()

project(Live2DRenderer CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Required when linking static Framework/GLEW/Core into a shared .so on Linux.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

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

# Platform-specific Core library paths
if(WIN32)
  # /MT ↔ Core_MT.lib (Release), /MTd ↔ Core_MTd.lib (Debug). Do not mix with *_MD*.
  set(CORE_LIB_RELEASE "${CORE_PATH}/lib/windows/x86_64/143/Live2DCubismCore_MT.lib")
  set(CORE_LIB_DEBUG "${CORE_PATH}/lib/windows/x86_64/143/Live2DCubismCore_MTd.lib")
elseif(UNIX AND NOT APPLE)
  # Linux: static Core library (typically libLive2DCubismCore.a)
  set(CORE_LIB_RELEASE "${CORE_PATH}/lib/linux/x86_64/libLive2DCubismCore.a")
  set(CORE_LIB_DEBUG "${CORE_LIB_RELEASE}")  # Linux SDK typically provides one static lib
else()
  message(FATAL_ERROR "Unsupported platform. Only Windows and Linux x86_64 are supported.")
endif()

# Verify required SDK files exist
set(REQUIRED_PATHS
    "${GLEW_PATH}/include/GL/glew.h"
    "${STB_PATH}/stb_image.h"
    "${FRAMEWORK_SHADER_PATH}/FragShaderSrc.frag"
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  list(APPEND REQUIRED_PATHS "${CORE_LIB_DEBUG}")
else()
  list(APPEND REQUIRED_PATHS "${CORE_LIB_RELEASE}")
endif()

foreach(required_path IN LISTS REQUIRED_PATHS)
  if(NOT EXISTS "${required_path}")
    message(FATAL_ERROR "Missing required Cubism SDK file: ${required_path}")
  endif()
endforeach()

message(STATUS "Using Cubism SDK at: ${CUBISM_SDK_ROOT}")
if(MSVC)
  message(STATUS "MSVC runtime: static (/MT) — DLL will not depend on VCRUNTIME/MSVCP")
else()
  message(STATUS "Building for Linux x86_64")
endif()

# GLEW (static)
set(BUILD_UTILS OFF CACHE BOOL "" FORCE)
add_subdirectory("${GLEW_PATH}/build/cmake" "${CMAKE_CURRENT_BINARY_DIR}/glew")

# Live2D Cubism Core (static import of official .lib/.a; not redistributed by this repo)
add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore PROPERTIES
  IMPORTED_LOCATION_RELEASE "${CORE_LIB_RELEASE}"
  IMPORTED_LOCATION_RELWITHDEBINFO "${CORE_LIB_RELEASE}"
  IMPORTED_LOCATION_MINSIZEREL "${CORE_LIB_RELEASE}"
  IMPORTED_LOCATION_DEBUG "${CORE_LIB_DEBUG}"
  INTERFACE_INCLUDE_DIRECTORIES "${CORE_PATH}/include"
)

# Framework
set(FRAMEWORK_SOURCE OpenGL)
add_subdirectory("${FRAMEWORK_PATH}" "${CMAKE_CURRENT_BINARY_DIR}/Framework")

if(WIN32)
  target_compile_definitions(Framework PUBLIC CSM_TARGET_WIN_GL GLEW_STATIC)
elseif(UNIX AND NOT APPLE)
  target_compile_definitions(Framework PUBLIC CSM_TARGET_LINUX_GL GLEW_STATIC)
endif()

target_include_directories(Framework PUBLIC
  "${GLEW_PATH}/include"
  "${CORE_PATH}/include"
)
target_link_libraries(Framework Live2DCubismCore glew_s)

# Shared library target
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
  GLEW_STATIC
)

if(WIN32)
  target_compile_definitions(live2d_renderer PRIVATE CSM_TARGET_WIN_GL)
elseif(UNIX AND NOT APPLE)
  target_compile_definitions(live2d_renderer PRIVATE CSM_TARGET_LINUX_GL)
endif()

target_include_directories(live2d_renderer PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/include"
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${CORE_PATH}/include"
  "${FRAMEWORK_PATH}/src"
  "${GLEW_PATH}/include"
  "${STB_PATH}"
)

if(WIN32)
  target_link_libraries(live2d_renderer PRIVATE
    Framework
    Live2DCubismCore
    glew_s
    opengl32
  )
elseif(UNIX AND NOT APPLE)
  target_link_libraries(live2d_renderer PRIVATE
    Framework
    Live2DCubismCore
    glew_s
    GL
    X11
    pthread
    dl
  )
endif()

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
