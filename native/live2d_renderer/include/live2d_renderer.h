#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef _WIN32
  #ifdef LIVE2D_RENDERER_EXPORTS
    #define LIVE2D_API __declspec(dllexport)
  #else
    #define LIVE2D_API __declspec(dllimport)
  #endif
#else
  #define LIVE2D_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef void* Live2DModelHandle;

/**
 * @brief Initialize the global Live2D Cubism runtime.
 * Must be called with an active OpenGL context before creating any models.
 * @return 1 on success, 0 on failure.
 */
LIVE2D_API int Live2D_Init();

/**
 * @brief Initialize a private hidden-window OpenGL context for offscreen rendering.
 * @return 1 on success, 0 on failure. Use Live2D_GetLastError for details.
 */
LIVE2D_API int Live2D_InitOffscreen();

/**
 * @brief Shutdown global Live2D Cubism runtime and release global resources.
 */
LIVE2D_API void Live2D_Shutdown();

/**
 * @brief Create and load a Live2D model from a .model3.json or .moc3 file path (UTF-8).
 * @param modelFilePath Full path to .model3.json or .moc3.
 * @return Handle to the created model, or NULL on failure.
 */
LIVE2D_API Live2DModelHandle Live2D_CreateModel(const char* modelFilePath);

/**
 * @brief Create a model from an in-memory QDPREVIEW bundle produced by quadrism.
 * The native renderer copies every asset it needs before this function returns.
 */
LIVE2D_API Live2DModelHandle Live2D_CreateModelFromMemory(
    const uint8_t* bundleData,
    size_t bundleSize);

/**
 * @brief Destroy a Live2D model instance and free its OpenGL textures and buffers.
 * @param handle Model handle returned by Live2D_CreateModel.
 */
LIVE2D_API void Live2D_DestroyModel(Live2DModelHandle handle);

/**
 * @brief Update model physics, animations, breath, eye blink, and look-at target.
 * @param handle Model handle.
 * @param deltaTime Seconds elapsed since previous frame.
 */
LIVE2D_API void Live2D_Update(Live2DModelHandle handle, float deltaTime);

/**
 * @brief Render model to current OpenGL framebuffer / viewport.
 * @param handle Model handle.
 * @param viewportWidth Width of target viewport in pixels.
 * @param viewportHeight Height of target viewport in pixels.
 * @param scale Custom scale factor (1.0 = fit to view).
 * @param offsetX Horizontal offset [-1.0, 1.0].
 * @param offsetY Vertical offset [-1.0, 1.0].
 */
LIVE2D_API void Live2D_Draw(Live2DModelHandle handle, int viewportWidth, int viewportHeight, float scale, float offsetX, float offsetY);

/**
 * @brief Set look-at mouse dragging target in normalized coordinates [-1.0, 1.0].
 * @param handle Model handle.
 * @param x Normalized X [-1.0 (left) to 1.0 (right)].
 * @param y Normalized Y [-1.0 (bottom) to 1.0 (top)].
 */
LIVE2D_API void Live2D_SetDragging(Live2DModelHandle handle, float x, float y);

/**
 * @brief Start a specific motion by group name and index.
 * @param handle Model handle.
 * @param group Motion group name (e.g. "Idle", "TapBody").
 * @param no Motion index inside group.
 * @param priority Priority level (0=none, 1=idle, 2=normal, 3=force).
 * @return 1 on success, 0 on failure.
 */
LIVE2D_API int Live2D_StartMotion(Live2DModelHandle handle, const char* group, int no, int priority);

/**
 * @brief Start a random motion in specified motion group.
 * @param handle Model handle.
 * @param group Motion group name.
 * @param priority Priority level.
 * @return 1 on success, 0 on failure.
 */
LIVE2D_API int Live2D_StartRandomMotion(Live2DModelHandle handle, const char* group, int priority);

/**
 * @brief Set facial expression by expression ID/name.
 * @param handle Model handle.
 * @param expressionId Expression ID (e.g. "f01").
 * @return 1 on success, 0 on failure.
 */
LIVE2D_API int Live2D_SetExpression(Live2DModelHandle handle, const char* expressionId);

/**
 * @brief Get JSON-encoded model information (motion groups, expressions, canvas size, etc.).
 * Caller must NOT free the returned string pointer; it is valid until next model call.
 * @param handle Model handle.
 * @return JSON string or NULL.
 */
LIVE2D_API const char* Live2D_GetModelInfoJson(Live2DModelHandle handle);

/**
 * @brief Perform hit testing at normalized coordinates [-1.0, 1.0].
 * @param handle Model handle.
 * @param hitAreaName Name of hit area (e.g. "Head", "Body").
 * @param x Normalized X.
 * @param y Normalized Y.
 * @return 1 if hit, 0 otherwise.
 */
LIVE2D_API int Live2D_HitTest(Live2DModelHandle handle, const char* hitAreaName, float x, float y);

LIVE2D_API void Live2D_SetParameterValue(Live2DModelHandle handle, const char* paramId, float value);
LIVE2D_API void Live2D_RefreshModel(Live2DModelHandle handle);
LIVE2D_API float Live2D_GetParameterValue(Live2DModelHandle handle, const char* paramId);
LIVE2D_API int Live2D_GetParameterCount(Live2DModelHandle handle);
LIVE2D_API int Live2D_CopyParameterValues(
    Live2DModelHandle handle,
    float* outValues,
    int capacity);

/**
 * @brief Render a frame into caller-owned RGBA8888 memory.
 */
LIVE2D_API int Live2D_RenderToRgba(
    Live2DModelHandle handle,
    int width,
    int height,
    float scale,
    float offsetX,
    float offsetY,
    uint8_t* outRgba);

/**
 * @brief Return the most recent error for the calling thread.
 * The pointer stays valid until the next Live2D API call on that thread.
 */
LIVE2D_API const char* Live2D_GetLastError();

#ifdef __cplusplus
}
#endif

