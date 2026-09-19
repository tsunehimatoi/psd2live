#include "live2d_pal.h"
#include <windows.h>
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

static Csm::csmByte* ReadBinaryFile(const std::wstring& wideStr, Csm::csmSizeInt* outSize)
{
    struct _stat statBuf;
    if (_wstat(wideStr.c_str(), &statBuf) != 0 || statBuf.st_size <= 0)
    {
        return nullptr;
    }

    int size = statBuf.st_size;
    std::ifstream file(wideStr, std::ios::in | std::ios::binary);
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

Csm::csmByte* Live2DPal::LoadFileAsBytes(const Csm::csmChar* filePath, Csm::csmSizeInt* outSize)
{
    if (!filePath || !outSize) return nullptr;

    wchar_t wideStr[MAX_PATH * 2];
    MultiByteToWideChar(CP_UTF8, 0U, filePath, -1, wideStr, MAX_PATH * 2);

    Csm::csmByte* res = ReadBinaryFile(wideStr, outSize);
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
        MultiByteToWideChar(CP_UTF8, 0U, candidate.c_str(), -1, wideStr, MAX_PATH * 2);
        res = ReadBinaryFile(wideStr, outSize);
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
    vsnprintf_s(buf, sizeof(buf), format, args);
    std::cout << buf;
    va_end(args);
}

void Live2DPal::PrintLogLn(const Csm::csmChar* format, ...)
{
    va_list args;
    char buf[1024];
    va_start(args, format);
    vsnprintf_s(buf, sizeof(buf), format, args);
    std::cout << buf << std::endl;
    va_end(args);
}
