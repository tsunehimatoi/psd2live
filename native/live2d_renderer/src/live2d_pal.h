#pragma once

#include <CubismFramework.hpp>
#include <ICubismAllocator.hpp>
#include <string>

class Live2DAllocator : public Csm::ICubismAllocator
{
public:
    virtual void* Allocate(const Csm::csmSizeType size) override;
    virtual void Deallocate(void* memory) override;
    virtual void* AllocateAligned(const Csm::csmSizeType size, const Csm::csmUint32 alignment) override;
    virtual void DeallocateAligned(void* alignedMemory) override;
};

class Live2DPal
{
public:
    static Csm::csmByte* LoadFileAsBytes(const Csm::csmChar* filePath, Csm::csmSizeInt* outSize);
    static void ReleaseBytes(Csm::csmByte* byteData);
    static void PrintLog(const Csm::csmChar* format, ...);
    static void PrintLogLn(const Csm::csmChar* format, ...);
    static void SetShaderDirectory(const std::string& dir);
    static std::string GetShaderDirectory();

private:
    static std::string s_shaderDir;
};
