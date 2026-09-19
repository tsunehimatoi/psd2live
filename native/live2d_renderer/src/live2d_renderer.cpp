#include "live2d_renderer.h"
#include "live2d_model.h"
#include "live2d_pal.h"

#include <windows.h>
#include <cstring>
#include <iostream>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

static Live2DAllocator s_allocator;
static bool s_isInitialized = false;
static std::mutex s_mutex;
static thread_local std::string s_lastError;

static void ClearLastError()
{
    s_lastError.clear();
}

static int Fail(const std::string& message)
{
    s_lastError = message;
    Live2DPal::PrintLogLn("[Live2D] %s", message.c_str());
    return 0;
}

static void CoreLogHandler(const char* message)
{
    std::cout << message << std::endl;
}

#ifdef _WIN32
static std::string GetCurrentDllDirectory()
{
    char path[MAX_PATH];
    HMODULE hm = NULL;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
        (LPCSTR)&Live2D_Init, &hm))
    {
        GetModuleFileNameA(hm, path, sizeof(path));
        std::string p(path);
        size_t lastSlash = p.find_last_of("\\/");
        if (lastSlash != std::string::npos)
        {
            return p.substr(0, lastSlash);
        }
    }
    return ".";
}
#endif

static HWND s_offscreenHwnd = NULL;
static HDC s_offscreenHdc = NULL;
static HGLRC s_offscreenHglrc = NULL;
static GLuint s_fbo = 0;
static GLuint s_fboColorTex = 0;
static GLuint s_fboDepthRb = 0;
static int s_fboWidth = 0;
static int s_fboHeight = 0;

static bool InitializeFramework()
{
    if (s_isInitialized) return true;

    static Csm::CubismFramework::Option s_frameworkOption;
    s_frameworkOption.LogFunction = CoreLogHandler;
    s_frameworkOption.LoggingLevel = Csm::CubismFramework::Option::LogLevel_Warning;
    s_frameworkOption.LoadFileFunction = Live2DPal::LoadFileAsBytes;
    s_frameworkOption.ReleaseBytesFunction = Live2DPal::ReleaseBytes;

    if (!Csm::CubismFramework::StartUp(&s_allocator, &s_frameworkOption))
    {
        Fail("CubismFramework::StartUp failed");
        return false;
    }
    Csm::CubismFramework::Initialize();

    const std::string dllDir = GetCurrentDllDirectory();
    Live2DPal::SetShaderDirectory(dllDir + "/FrameworkShaders");
    s_isInitialized = true;
    return true;
}

static bool InitializeOffscreenLocked()
{
    if (!s_offscreenHglrc)
    {
        WNDCLASSA wc = {0};
        wc.lpfnWndProc = DefWindowProcA;
        wc.hInstance = GetModuleHandle(NULL);
        wc.lpszClassName = "Live2D_Offscreen_Host_Class";
        if (!RegisterClassA(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
        {
            Fail("Cannot register the offscreen OpenGL window class");
            return false;
        }

        s_offscreenHwnd = CreateWindowExA(0, "Live2D_Offscreen_Host_Class", "Live2D_Offscreen_Host",
            WS_POPUP, 0, 0, 64, 64, NULL, NULL, GetModuleHandle(NULL), NULL);
        if (!s_offscreenHwnd)
        {
            Fail("Cannot create the offscreen OpenGL host window");
            return false;
        }

        s_offscreenHdc = GetDC(s_offscreenHwnd);
        if (!s_offscreenHdc)
        {
            Fail("Cannot acquire the offscreen OpenGL device context");
            return false;
        }
        PIXELFORMATDESCRIPTOR pfd = {
            sizeof(PIXELFORMATDESCRIPTOR), 1,
            PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER,
            PFD_TYPE_RGBA, 32,
            0, 0, 0, 0, 0, 0, 8, 0,
            0, 0, 0, 0, 0, 24, 8, 0,
            PFD_MAIN_PLANE, 0, 0, 0, 0
        };

        int maxFormats = DescribePixelFormat(s_offscreenHdc, 1, sizeof(pfd), &pfd);
        int chosenFormat = 0;
        for (int i = 1; i <= maxFormats; i++)
        {
            PIXELFORMATDESCRIPTOR cur;
            DescribePixelFormat(s_offscreenHdc, i, sizeof(cur), &cur);
            if ((cur.dwFlags & PFD_SUPPORT_OPENGL) &&
                (cur.dwFlags & PFD_DRAW_TO_WINDOW) &&
                (cur.dwFlags & PFD_DOUBLEBUFFER) &&
                !(cur.dwFlags & PFD_GENERIC_FORMAT) &&
                cur.iPixelType == PFD_TYPE_RGBA &&
                cur.cColorBits >= 24)
            {
                chosenFormat = i;
                pfd = cur;
                break;
            }
        }
        if (chosenFormat == 0) chosenFormat = ChoosePixelFormat(s_offscreenHdc, &pfd);
        if (chosenFormat == 0 || !SetPixelFormat(s_offscreenHdc, chosenFormat, &pfd))
        {
            Fail("Cannot set an accelerated offscreen OpenGL pixel format");
            return false;
        }
        s_offscreenHglrc = wglCreateContext(s_offscreenHdc);
        if (!s_offscreenHglrc)
        {
            Fail("Cannot create the offscreen OpenGL context");
            return false;
        }
    }
    if (!wglMakeCurrent(s_offscreenHdc, s_offscreenHglrc))
    {
        Fail("Cannot activate the offscreen OpenGL context");
        return false;
    }

    // Initialize GLEW
    glewExperimental = GL_TRUE;
    GLenum err = glewInit();
    if (GLEW_OK != err)
    {
        Fail(std::string("GLEW initialization failed: ") +
            reinterpret_cast<const char*>(glewGetErrorString(err)));
        return false;
    }
    // GLEW may leave GL_INVALID_ENUM behind when probing a legacy WGL context.
    while (glGetError() != GL_NO_ERROR) {}

    if (!InitializeFramework())
    {
        return false;
    }

    const auto* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const auto* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    Live2DPal::PrintLogLn("[Live2D] Runtime initialized offscreen on GPU: %s (%s)",
        renderer ? renderer : "unknown", version ? version : "unknown");
    return true;
}

int Live2D_InitOffscreen()
{
    std::lock_guard<std::mutex> lock(s_mutex);
    ClearLastError();
    if (!InitializeOffscreenLocked()) return 0;
    return 1;
}

static void EnsureFbo(int width, int height)
{
    if (s_fbo != 0 && s_fboWidth == width && s_fboHeight == height) return;

    if (s_fbo != 0)
    {
        glDeleteFramebuffers(1, &s_fbo);
        glDeleteTextures(1, &s_fboColorTex);
        glDeleteRenderbuffers(1, &s_fboDepthRb);
        s_fbo = 0;
    }

    s_fboWidth = width;
    s_fboHeight = height;

    glGenFramebuffers(1, &s_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, s_fbo);

    glGenTextures(1, &s_fboColorTex);
    glBindTexture(GL_TEXTURE_2D, s_fboColorTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_fboColorTex, 0);

    glGenRenderbuffers(1, &s_fboDepthRb);
    glBindRenderbuffer(GL_RENDERBUFFER, s_fboDepthRb);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, s_fboDepthRb);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE)
    {
        Live2DPal::PrintLogLn("[Live2D] Warning: FBO status incomplete: 0x%x", status);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

int Live2D_Init()
{
    std::lock_guard<std::mutex> lock(s_mutex);
    ClearLastError();

    // Initialize GLEW
    glewExperimental = GL_TRUE;
    GLenum err = glewInit();
    if (GLEW_OK != err)
    {
        Live2DPal::PrintLogLn("[Live2D] glewInit info: %s", glewGetErrorString(err));
    }
    else
    {
        Live2DPal::PrintLogLn("[Live2D] GLEW initialized successfully. Version: %s", glewGetString(GLEW_VERSION));
        Live2DPal::PrintLogLn("[Live2D] glGenFramebuffers: %p, glGenFramebuffersEXT: %p", glGenFramebuffers, glGenFramebuffersEXT);
    }

    if (!InitializeFramework()) return 0;

    return 1;
}

void Live2D_Shutdown()
{
    std::lock_guard<std::mutex> lock(s_mutex);
    if (s_isInitialized)
    {
        Csm::CubismFramework::Dispose();
        Csm::CubismFramework::CleanUp();
        s_isInitialized = false;
    }
}

Live2DModelHandle Live2D_CreateModel(const char* modelFilePath)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    ClearLastError();
    if (!modelFilePath || modelFilePath[0] == '\0')
    {
        Fail("Model manifest path is empty");
        return nullptr;
    }
    if (s_offscreenHglrc)
    {
        if (!wglMakeCurrent(s_offscreenHdc, s_offscreenHglrc))
        {
            Fail("Cannot activate OpenGL while loading the model");
            return nullptr;
        }
    }
    else if (!s_isInitialized)
    {
        Fail("Live2D runtime is not initialized");
        return nullptr;
    }

    Live2DModel* model = new Live2DModel();
    if (!model->LoadAssets(modelFilePath))
    {
        delete model;
        Fail("Cannot load the Live2D model manifest or its referenced assets");
        return nullptr;
    }

    return reinterpret_cast<Live2DModelHandle>(model);
}

namespace
{
constexpr char kPreviewMagic[] = "QDPREVIEW";

bool ReadU32(const uint8_t*& cursor, const uint8_t* end, uint32_t& value)
{
    if (static_cast<size_t>(end - cursor) < sizeof(value)) return false;
    std::memcpy(&value, cursor, sizeof(value));
    cursor += sizeof(value);
    return true;
}

bool ReadU64(const uint8_t*& cursor, const uint8_t* end, uint64_t& value)
{
    if (static_cast<size_t>(end - cursor) < sizeof(value)) return false;
    std::memcpy(&value, cursor, sizeof(value));
    cursor += sizeof(value);
    return true;
}
}

Live2DModelHandle Live2D_CreateModelFromMemory(const uint8_t* bundleData, size_t bundleSize)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    ClearLastError();
    if (!bundleData || bundleSize < 21)
    {
        Fail("Rust preview bundle is empty or truncated");
        return nullptr;
    }
    if (!InitializeOffscreenLocked()) return nullptr;

    const uint8_t* cursor = bundleData;
    const uint8_t* end = bundleData + bundleSize;
    if (std::memcmp(cursor, kPreviewMagic, sizeof(kPreviewMagic) - 1) != 0)
    {
        Fail("Rust preview bundle has an invalid signature");
        return nullptr;
    }
    cursor += sizeof(kPreviewMagic) - 1;

    uint32_t version = 0;
    uint32_t manifestLength = 0;
    uint32_t assetCount = 0;
    if (!ReadU32(cursor, end, version) || version != 1 ||
        !ReadU32(cursor, end, manifestLength) ||
        !ReadU32(cursor, end, assetCount) ||
        manifestLength == 0 || manifestLength > static_cast<uint32_t>(end - cursor) ||
        assetCount == 0 || assetCount > 10000)
    {
        Fail("Rust preview bundle header is invalid");
        return nullptr;
    }

    std::string manifestPath(reinterpret_cast<const char*>(cursor), manifestLength);
    cursor += manifestLength;
    std::vector<Live2DModel::MemoryAsset> assets;
    assets.reserve(assetCount);
    for (uint32_t index = 0; index < assetCount; ++index)
    {
        uint32_t pathLength = 0;
        uint64_t dataLength = 0;
        if (!ReadU32(cursor, end, pathLength) || !ReadU64(cursor, end, dataLength) ||
            pathLength == 0 || pathLength > static_cast<uint32_t>(end - cursor) ||
            dataLength > static_cast<uint64_t>(end - cursor - pathLength) ||
            dataLength > static_cast<uint64_t>((std::numeric_limits<size_t>::max)()))
        {
            Fail("Rust preview bundle contains a truncated asset");
            return nullptr;
        }
        Live2DModel::MemoryAsset asset;
        asset.path.assign(reinterpret_cast<const char*>(cursor), pathLength);
        cursor += pathLength;
        asset.data.assign(cursor, cursor + static_cast<size_t>(dataLength));
        cursor += static_cast<size_t>(dataLength);
        assets.push_back(std::move(asset));
    }
    if (cursor != end)
    {
        Fail("Rust preview bundle has trailing bytes");
        return nullptr;
    }

    auto* model = new Live2DModel();
    if (!model->LoadAssetsFromMemory(manifestPath, std::move(assets)))
    {
        delete model;
        Fail("NativeSDK could not create the in-memory Live2D model");
        return nullptr;
    }
    return reinterpret_cast<Live2DModelHandle>(model);
}

void Live2D_DestroyModel(Live2DModelHandle handle)
{
    if (!handle) return;
    std::lock_guard<std::mutex> lock(s_mutex);
    if (s_offscreenHglrc) wglMakeCurrent(s_offscreenHdc, s_offscreenHglrc);
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    delete model;
}

void Live2D_Update(Live2DModelHandle handle, float deltaTime)
{
    if (!handle) return;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    model->Update(deltaTime);
}

void Live2D_Draw(Live2DModelHandle handle, int viewportWidth, int viewportHeight, float scale, float offsetX, float offsetY)
{
    if (!handle) return;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    model->Draw(viewportWidth, viewportHeight, scale, offsetX, offsetY);
}

void Live2D_SetDragging(Live2DModelHandle handle, float x, float y)
{
    if (!handle) return;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    model->SetDragging(x, y);
}

int Live2D_StartMotion(Live2DModelHandle handle, const char* group, int no, int priority)
{
    if (!handle || !group) return 0;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->StartMotion(group, no, priority) ? 1 : 0;
}

int Live2D_StartRandomMotion(Live2DModelHandle handle, const char* group, int priority)
{
    if (!handle || !group) return 0;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->StartRandomMotion(group, priority) ? 1 : 0;
}

int Live2D_SetExpression(Live2DModelHandle handle, const char* expressionId)
{
    if (!handle || !expressionId) return 0;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->SetExpression(expressionId) ? 1 : 0;
}

const char* Live2D_GetModelInfoJson(Live2DModelHandle handle)
{
    if (!handle) return nullptr;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->GetModelInfoJson().c_str();
}

int Live2D_HitTest(Live2DModelHandle handle, const char* hitAreaName, float x, float y)
{
    if (!handle || !hitAreaName) return 0;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->HitTest(hitAreaName, x, y) ? 1 : 0;
}

void Live2D_SetParameterValue(Live2DModelHandle handle, const char* paramId, float value)
{
    if (!handle || !paramId) return;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    model->SetParameterValue(paramId, value);
}

void Live2D_RefreshModel(Live2DModelHandle handle)
{
    if (!handle) return;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    model->RefreshModel();
}

float Live2D_GetParameterValue(Live2DModelHandle handle, const char* paramId)
{
    if (!handle || !paramId) return 0.0f;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->GetParameterValue(paramId);
}

int Live2D_GetParameterCount(Live2DModelHandle handle)
{
    if (!handle) return 0;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->GetParameterCount();
}

int Live2D_CopyParameterValues(Live2DModelHandle handle, float* outValues, int capacity)
{
    if (!handle || !outValues || capacity <= 0) return 0;
    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    return model->CopyParameterValues(outValues, capacity);
}

int Live2D_RenderToRgba(Live2DModelHandle handle, int width, int height, float scale, float offsetX, float offsetY, unsigned char* outRgba)
{
    std::lock_guard<std::mutex> lock(s_mutex);
    ClearLastError();
    if (!handle || !outRgba || width <= 0 || height <= 0)
    {
        return Fail("Invalid model, dimensions, or output buffer");
    }
    if (!s_offscreenHglrc)
    {
        if (!InitializeOffscreenLocked()) return 0;
    }
    if (!wglMakeCurrent(s_offscreenHdc, s_offscreenHglrc))
    {
        return Fail("Cannot activate OpenGL while rendering");
    }

    EnsureFbo(width, height);
    glBindFramebuffer(GL_FRAMEBUFFER, s_fbo);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
    {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return Fail("Offscreen framebuffer is incomplete");
    }
    glViewport(0, 0, width, height);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

    Live2DModel* model = reinterpret_cast<Live2DModel*>(handle);
    model->Draw(width, height, scale, offsetX, offsetY);

    // Re-bind s_fbo in case model drawing unbound it
    glBindFramebuffer(GL_FRAMEBUFFER, s_fbo);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, outRgba);

    // Vertical flip in place (OpenGL is bottom-left origin)
    int rowStride = width * 4;
    std::vector<unsigned char> tempRow(rowStride);
    for (int y = 0; y < height / 2; y++)
    {
        unsigned char* top = outRgba + y * rowStride;
        unsigned char* bottom = outRgba + (height - 1 - y) * rowStride;
        memcpy(tempRow.data(), top, rowStride);
        memcpy(top, bottom, rowStride);
        memcpy(bottom, tempRow.data(), rowStride);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    const GLenum glError = glGetError();
    if (glError != GL_NO_ERROR)
    {
        return Fail("OpenGL rendering failed with error " + std::to_string(glError));
    }
    return 1;
}

const char* Live2D_GetLastError()
{
    return s_lastError.c_str();
}
