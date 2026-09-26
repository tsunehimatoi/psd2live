#pragma once

#include <string>
#include <vector>
#include <map>
#include <unordered_map>

#include <GL/glew.h>

#include <CubismFramework.hpp>
#include <Model/CubismUserModel.hpp>
#include <CubismModelSettingJson.hpp>
#include <Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp>
#include <Motion/CubismMotion.hpp>
#include <Motion/CubismExpressionMotion.hpp>
#include <Physics/CubismPhysics.hpp>
#include <Effect/CubismEyeBlink.hpp>
#include <Effect/CubismBreath.hpp>
#include <Effect/CubismLook.hpp>
#include <Effect/CubismPose.hpp>
#include <Math/CubismMatrix44.hpp>
#include <Motion/CubismBreathUpdater.hpp>
#include <Motion/CubismLookUpdater.hpp>
#include <Motion/CubismExpressionUpdater.hpp>
#include <Motion/CubismEyeBlinkUpdater.hpp>
#include <Motion/CubismPhysicsUpdater.hpp>
#include <Motion/CubismPoseUpdater.hpp>

class Live2DModel : public Csm::CubismUserModel
{
public:
    struct MemoryAsset
    {
        std::string path;
        std::vector<unsigned char> data;
    };

    Live2DModel();
    virtual ~Live2DModel();

    bool LoadAssets(const std::string& modelFilePath);
    bool LoadAssetsFromMemory(
        const std::string& modelFilePath,
        std::vector<MemoryAsset> assets);
    void Update(float deltaTime);
    void Draw(int viewportWidth, int viewportHeight, float scale, float offsetX, float offsetY);

    void SetDragging(float x, float y);
    bool StartMotion(const char* group, int no, int priority);
    bool StartRandomMotion(const char* group, int priority);
    bool SetExpression(const char* expressionId);
    bool HitTest(const char* hitAreaName, float x, float y);
    void SetParameterValue(const char* paramId, float value);
    void RefreshModel();
    float GetParameterValue(const char* paramId);
    int GetParameterCount() const;
    int CopyParameterValues(float* outValues, int capacity) const;

    const std::string& GetModelInfoJson();

private:
    void SetupModel(Csm::ICubismModelSetting* setting);
    void SetupTextures();
    void PreloadMotionGroup(const Csm::csmChar* group);
    void ReleaseMotions();
    void ReleaseExpressions();
    void ReleaseTextures();

    Csm::csmByte* CreateBuffer(const Csm::csmChar* path, Csm::csmSizeInt* size);
    void DeleteBuffer(Csm::csmByte* buffer);

    std::string _modelHomeDir;
    std::string _modelFileName;
    std::unordered_map<std::string, std::vector<unsigned char>> _memoryFiles;
    Csm::ICubismModelSetting* _modelSetting;
    float _userTimeSeconds;

    Csm::csmVector<Csm::CubismIdHandle> _eyeBlinkIds;
    Csm::csmVector<Csm::CubismIdHandle> _lipSyncIds;

    std::map<std::string, Csm::ACubismMotion*> _motions;
    std::map<std::string, Csm::ACubismMotion*> _expressions;
    std::vector<GLuint> _textures;

    std::vector<std::string> _motionGroups;
    std::vector<std::string> _expressionNames;
    std::vector<std::string> _hitAreaNames;

    const Csm::CubismId* _idParamAngleX;
    const Csm::CubismId* _idParamAngleY;
    const Csm::CubismId* _idParamAngleZ;
    const Csm::CubismId* _idParamBodyAngleX;
    const Csm::CubismId* _idParamEyeBallX;
    const Csm::CubismId* _idParamEyeBallY;

    bool _motionUpdated;
    std::string _infoJsonCache;
    std::string _idleGroupName;
    bool _hasIdleGroup;
};

