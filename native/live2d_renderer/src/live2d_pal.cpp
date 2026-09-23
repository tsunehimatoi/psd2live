#include "live2d_pal.h"
#ifdef _WIN32
#include <windows.h>
#endif
#include <cstdio>
#include <cstdarg>
#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <sys/stat.h>

std::string Live2DPal::s_shaderDir = "";

void* Live2DAllocator::Allocate(const Csm::csmSizeType size)
{
    return malloc(size);
}

void Live2DAllocator::Deallocate(void* memory)
{
    free(memory);
}

void* Live2DAllocator::AllocateAligned(const Csm::csmSizeType size, const Csm::csmUint32 alignment)
{
    size_t offset, shift, alignedAddress;
    void* allocation;
    void** preamble;

    offset = alignment - 1 + sizeof(void*);
    allocation = Allocate(size + static_cast<Csm::csmUint32>(offset));
    if (!allocation) return nullptr;

    alignedAddress = reinterpret_cast<size_t>(allocation) + sizeof(void*);
    shift = alignedAddress % alignment;

    if (shift)
    {
        alignedAddress += (alignment - shift);
    }

    preamble = reinterpret_cast<void**>(alignedAddress);
    preamble[-1] = allocation;

    return reinterpret_cast<void*>(alignedAddress);
}

void Live2DAllocator::DeallocateAligned(void* alignedMemory)
{
    if (!alignedMemory) return;
    void** preamble = static_cast<void**>(alignedMemory);
    Deallocate(preamble[-1]);
}

void Live2DPal::SetShaderDirectory(const std::string& dir)
{
    s_shaderDir = dir;
}

std::string Live2DPal::GetShaderDirectory()
{
    return s_shaderDir;
}

#ifdef _WIN32
// UTF-8 path → wchar_t for Windows filesystem APIs (non-ASCII model / shader paths).
static bool Utf8ToWide(const std::string& utf8, std::wstring& outWide)
{
    if (utf8.empty())
    {
        outWide.clear();
        return false;
    }
    const int needed = MultiByteToWideChar(CP_UTF8, 0U, utf8.c_str(), -1, nullptr, 0);
    if (needed <= 0) return false;
    outWide.assign(static_cast<size_t>(needed), L'\0');
    if (MultiByteToWideChar(CP_UTF8, 0U, utf8.c_str(), -1, &outWide[0], needed) <= 0)
    {
        outWide.clear();
        return false;
    }
    // Drop the trailing null MultiByteToWideChar wrote into the buffer.
    if (!outWide.empty() && outWide.back() == L'\0') outWide.pop_back();
    return !outWide.empty();
}

static Csm::csmByte* ReadBinaryFileWide(const std::wstring& widePath, Csm::csmSizeInt* outSize)
{
    struct _stat statBuf;
    if (_wstat(widePath.c_str(), &statBuf) != 0 || statBuf.st_size <= 0)
    {
        return nullptr;
    }

    int size = static_cast<int>(statBuf.st_size);
    std::ifstream file(widePath, std::ios::in | std::ios::binary);
    if (!file.is_open())
    {
        return nullptr;
    }

    char* buf = new char[size + 1];
    file.read(buf, size);
    buf[size] = '\0';
    file.close();

    *outSize = size;
    return reinterpret_cast<Csm::csmByte*>(buf);
}

static Csm::csmByte* ReadBinaryFile(const std::string& path, Csm::csmSizeInt* outSize)
{
    std::wstring widePath;
    if (!Utf8ToWide(path, widePath)) return nullptr;
    return ReadBinaryFileWide(widePath, outSize);
}
#else
static Csm::csmByte* ReadBinaryFile(const std::string& path, Csm::csmSizeInt* outSize)
{
    struct stat statBuf;
    if (stat(path.c_str(), &statBuf) != 0 || statBuf.st_size <= 0)
    {
        return nullptr;
    }

    int size = static_cast<int>(statBuf.st_size);
    std::ifstream file(path, std::ios::in | std::ios::binary);
    if (!file.is_open())
    {
        return nullptr;
    }

    char* buf = new char[size + 1];
    file.read(buf, size);
    buf[size] = '\0';
    file.close();

    *outSize = size;
    return reinterpret_cast<Csm::csmByte*>(buf);
}
#endif

Csm::csmByte* Live2DPal::LoadFileAsBytes(const std::string filePath, Csm::csmSizeInt* outSize)
{
    if (filePath.empty() || !outSize) return nullptr;

    Csm::csmByte* res = ReadBinaryFile(filePath, outSize);
    if (res)
    {
        return res;
    }

    // Try finding shader in shader directory and standard locations
    std::string filename = filePath;
    size_t lastSlash = filename.find_last_of("/\\");
    if (lastSlash != std::string::npos)
    {
        filename = filename.substr(lastSlash + 1);
    }

    std::vector<std::string> searchDirs;
    if (!s_shaderDir.empty())
    {
        searchDirs.push_back(s_shaderDir);
        searchDirs.push_back(s_shaderDir + "/Standard");
        searchDirs.push_back(s_shaderDir + "/FrameworkShaders");
    }
    searchDirs.push_back("FrameworkShaders");
    searchDirs.push_back("FrameworkShaders/Standard");
    searchDirs.push_back("./FrameworkShaders");
    searchDirs.push_back("./data/flutter_assets/FrameworkShaders");

    for (const auto& dir : searchDirs)
    {
        std::string candidate = dir + "/" + filename;
        res = ReadBinaryFile(candidate, outSize);
        if (res)
        {
            return res;
        }
    }

    return nullptr;
}

void Live2DPal::ReleaseBytes(Csm::csmByte* byteData)
{
    if (byteData)
    {
        delete[] byteData;
    }
}

void Live2DPal::PrintLog(const Csm::csmChar* format, ...)
{
    va_list args;
    char buf[1024];
    va_start(args, format);
#ifdef _WIN32
    vsnprintf_s(buf, sizeof(buf), format, args);
#else
    vsnprintf(buf, sizeof(buf), format, args);
#endif
    std::cout << buf;
    va_end(args);
}

void Live2DPal::PrintLogLn(const Csm::csmChar* format, ...)
{
    va_list args;
    char buf[1024];
    va_start(args, format);
#ifdef _WIN32
    vsnprintf_s(buf, sizeof(buf), format, args);
#else
    vsnprintf(buf, sizeof(buf), format, args);
#endif
    std::cout << buf << std::endl;
    va_end(args);
}
