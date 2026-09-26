#include "live2d_model.h"
#include "live2d_pal.h"

#include <iostream>
#include <sstream>
#include <fstream>
#include <algorithm>
#include <cstring>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_STDIO
#define STBI_ONLY_PNG
#include "stb_image.h"

#include <CubismDefaultParameterId.hpp>
#include <Utils/CubismString.hpp>
#include <Id/CubismIdManager.hpp>
#include <Motion/CubismMotionQueueEntry.hpp>

using namespace Live2D::Cubism::Framework;
using namespace Live2D::Cubism::Framework::DefaultParameterId;

namespace
{
    // The priorities of the official samples: an idle yields to everything, a forced motion to nothing.
    constexpr int PriorityIdle = 1;
    constexpr int PriorityForce = 3;
}

Live2DModel::Live2DModel()
    : _modelSetting(nullptr)
    , _userTimeSeconds(0.0f)
    , _motionUpdated(false)
    , _idParamAngleX(nullptr)
    , _idParamAngleY(nullptr)
    , _idParamAngleZ(nullptr)
    , _idParamBodyAngleX(nullptr)
    , _idParamEyeBallX(nullptr)
    , _idParamEyeBallY(nullptr)
    , _idleGroupName("")
    , _hasIdleGroup(false)
{
    _mocConsistency = true;
    _motionConsistency = true;

    _idParamAngleX = CubismFramework::GetIdManager()->GetId(ParamAngleX);
    _idParamAngleY = CubismFramework::GetIdManager()->GetId(ParamAngleY);
    _idParamAngleZ = CubismFramework::GetIdManager()->GetId(ParamAngleZ);
    _idParamBodyAngleX = CubismFramework::GetIdManager()->GetId(ParamBodyAngleX);
    _idParamEyeBallX = CubismFramework::GetIdManager()->GetId(ParamEyeBallX);
    _idParamEyeBallY = CubismFramework::GetIdManager()->GetId(ParamEyeBallY);
}

Live2DModel::~Live2DModel()
{
    ReleaseMotions();
    ReleaseExpressions();
    ReleaseTextures();

    if (_modelSetting)
    {
        delete _modelSetting;
        _modelSetting = nullptr;
    }

    DeleteRenderer();
}

Csm::csmByte* Live2DModel::CreateBuffer(const Csm::csmChar* path, Csm::csmSizeInt* size)
{
    if (path && size && !_memoryFiles.empty())
    {
        std::string key = path;
        std::replace(key.begin(), key.end(), '\\', '/');
        while (key.rfind("./", 0) == 0)
        {
            key.erase(0, 2);
        }

        const auto found = _memoryFiles.find(key);
        if (found != _memoryFiles.end())
        {
            const auto& source = found->second;
            auto* copy = new Csm::csmByte[source.size() + 1];
            if (!source.empty())
            {
                std::memcpy(copy, source.data(), source.size());
            }
            copy[source.size()] = 0;
            *size = static_cast<Csm::csmSizeInt>(source.size());
            return copy;
        }
        return nullptr;
    }
    return Live2DPal::LoadFileAsBytes(path, size);
}

void Live2DModel::DeleteBuffer(Csm::csmByte* buffer)
{
    Live2DPal::ReleaseBytes(buffer);
}

bool Live2DModel::LoadAssets(const std::string& modelFilePath)
{
    std::string path = modelFilePath;
    std::replace(path.begin(), path.end(), '\\', '/');

    size_t lastSlash = path.find_last_of('/');
    if (lastSlash != std::string::npos)
    {
        _modelHomeDir = path.substr(0, lastSlash + 1);
        _modelFileName = path.substr(lastSlash + 1);
    }
    else
    {
        _modelHomeDir = "./";
        _modelFileName = path;
    }

    Csm::csmSizeInt size = 0;
    Csm::csmByte* buffer = CreateBuffer(path.c_str(), &size);
    if (!buffer || size <= 0)
    {
        Live2DPal::PrintLogLn("[Live2D] Failed to read model setting file: %s", path.c_str());
        return false;
    }

    ICubismModelSetting* setting = new CubismModelSettingJson(buffer, size);
    DeleteBuffer(buffer);

    SetupModel(setting);

    if (!_model)
    {
        Live2DPal::PrintLogLn("[Live2D] Failed to create model: %s", path.c_str());
        return false;
    }

    CreateRenderer(1024, 1024, 1);
    SetupTextures();

    return true;
}

bool Live2DModel::LoadAssetsFromMemory(
    const std::string& modelFilePath,
    std::vector<MemoryAsset> assets)
{
    _memoryFiles.clear();
    for (auto& asset : assets)
    {
        std::replace(asset.path.begin(), asset.path.end(), '\\', '/');
        while (asset.path.rfind("./", 0) == 0)
        {
            asset.path.erase(0, 2);
        }
        _memoryFiles.emplace(std::move(asset.path), std::move(asset.data));
    }
    return LoadAssets(modelFilePath);
}

void Live2DModel::SetupModel(ICubismModelSetting* setting)
{
    _updating = true;
    _initialized = false;
    _modelSetting = setting;

    Csm::csmByte* buffer = nullptr;
    Csm::csmSizeInt size = 0;

    // Load MOC3
    if (std::strlen(_modelSetting->GetModelFileName()) > 0)
    {
        std::string mocPath = _modelHomeDir + _modelSetting->GetModelFileName();
        buffer = CreateBuffer(mocPath.c_str(), &size);
        if (buffer && size > 0)
        {
            LoadModel(buffer, size, _mocConsistency);
            DeleteBuffer(buffer);
        }
    }

    if (!_model)
    {
        Live2DPal::PrintLogLn("[Live2D] LoadModel failed.");
        _updating = false;
        return;
    }

    // Expressions
    if (_modelSetting->GetExpressionCount() > 0)
    {
        csmInt32 count = _modelSetting->GetExpressionCount();
        for (csmInt32 i = 0; i < count; i++)
        {
            std::string name = _modelSetting->GetExpressionName(i);
            std::string expPath = _modelHomeDir + _modelSetting->GetExpressionFileName(i);

            buffer = CreateBuffer(expPath.c_str(), &size);
            if (buffer && size > 0)
            {
                ACubismMotion* motion = LoadExpression(buffer, size, name.c_str());
                if (motion)
                {
                    if (_expressions[name] != nullptr)
                    {
                        ACubismMotion::Delete(_expressions[name]);
                    }
                    _expressions[name] = motion;
                    _expressionNames.push_back(name);
                }
                DeleteBuffer(buffer);
            }
        }
        CubismExpressionUpdater* expUpdater = CSM_NEW CubismExpressionUpdater(*_expressionManager);
        _updateScheduler.AddUpdatableList(expUpdater);
    }

    // Physics
    if (std::strlen(_modelSetting->GetPhysicsFileName()) > 0)
    {
        std::string physPath = _modelHomeDir + _modelSetting->GetPhysicsFileName();
        buffer = CreateBuffer(physPath.c_str(), &size);
        if (buffer && size > 0)
        {
            LoadPhysics(buffer, size);
            if (_physics != nullptr)
            {
                CubismPhysicsUpdater* physUpdater = CSM_NEW CubismPhysicsUpdater(*_physics);
                _updateScheduler.AddUpdatableList(physUpdater);
            }
            DeleteBuffer(buffer);
        }
    }

    // Pose
    if (std::strlen(_modelSetting->GetPoseFileName()) > 0)
    {
        std::string posePath = _modelHomeDir + _modelSetting->GetPoseFileName();
        buffer = CreateBuffer(posePath.c_str(), &size);
        if (buffer && size > 0)
        {
            LoadPose(buffer, size);
            if (_pose != nullptr)
            {
                CubismPoseUpdater* poseUpdater = CSM_NEW CubismPoseUpdater(*_pose);
                _updateScheduler.AddUpdatableList(poseUpdater);
            }
            DeleteBuffer(buffer);
        }
    }

    // EyeBlink
    if (_modelSetting->GetEyeBlinkParameterCount() > 0)
    {
        _eyeBlink = CubismEyeBlink::Create(_modelSetting);
        CubismEyeBlinkUpdater* eyeBlinkUpdater = CSM_NEW CubismEyeBlinkUpdater(_motionUpdated, *_eyeBlink);
        _updateScheduler.AddUpdatableList(eyeBlinkUpdater);
    }

    // Breath
    {
        _breath = CubismBreath::Create();
        csmVector<CubismBreath::BreathParameterData> breathParameters;
        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleX, 0.0f, 15.0f, 6.5345f, 0.5f));
        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleY, 0.0f, 8.0f, 3.5345f, 0.5f));
        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamAngleZ, 0.0f, 10.0f, 5.5345f, 0.5f));
        breathParameters.PushBack(CubismBreath::BreathParameterData(_idParamBodyAngleX, 0.0f, 4.0f, 15.5345f, 0.5f));
        breathParameters.PushBack(CubismBreath::BreathParameterData(CubismFramework::GetIdManager()->GetId(ParamBreath), 0.5f, 0.5f, 3.2345f, 0.5f));
        _breath->SetParameters(breathParameters);

        CubismBreathUpdater* breathUpdater = CSM_NEW CubismBreathUpdater(*_breath);
        _updateScheduler.AddUpdatableList(breathUpdater);
    }

    // Look Tracking
    {
        _look = CubismLook::Create();
        csmVector<CubismLook::LookParameterData> lookParameters;
        lookParameters.PushBack(CubismLook::LookParameterData(_idParamAngleX, 30.0f));
        lookParameters.PushBack(CubismLook::LookParameterData(_idParamAngleY, 0.0f, 30.0f));
        lookParameters.PushBack(CubismLook::LookParameterData(_idParamAngleZ, 0.0f, 0.0f, -30.0f));
        lookParameters.PushBack(CubismLook::LookParameterData(_idParamBodyAngleX, 10.0f));
        lookParameters.PushBack(CubismLook::LookParameterData(_idParamEyeBallX, 1.0f));
        lookParameters.PushBack(CubismLook::LookParameterData(_idParamEyeBallY, 0.0f, 1.0f));
        _look->SetParameters(lookParameters);

        CubismLookUpdater* lookUpdater = CSM_NEW CubismLookUpdater(*_look, *_dragManager);
        _updateScheduler.AddUpdatableList(lookUpdater);
    }

    _updateScheduler.SortUpdatableList();

    // Layout
    csmMap<csmString, csmFloat32> layout;
    _modelSetting->GetLayoutMap(layout);
    _modelMatrix->SetupFromLayout(layout);

    _model->SaveParameters();

    // Preload motions
    csmInt32 motionGroupCount = _modelSetting->GetMotionGroupCount();
    for (csmInt32 i = 0; i < motionGroupCount; i++)
    {
        const csmChar* group = _modelSetting->GetMotionGroupName(i);
        _motionGroups.push_back(group);

        std::string groupLower = group;
        std::transform(groupLower.begin(), groupLower.end(), groupLower.begin(), ::tolower);
        if (_idleGroupName.empty() && (groupLower == "idle" || groupLower.find("idle") != std::string::npos))
        {
            _idleGroupName = group;
            _hasIdleGroup = true;
        }
    }

    if (_idleGroupName.empty() && !_motionGroups.empty())
    {
        _idleGroupName = _motionGroups[0];
    }

    // Hit areas
    csmInt32 hitAreaCount = _modelSetting->GetHitAreasCount();
    for (csmInt32 i = 0; i < hitAreaCount; i++)
    {
        _hitAreaNames.push_back(_modelSetting->GetHitAreaName(i));
    }

    _motionManager->StopAllMotions();
    _updating = false;
    _initialized = true;

}

void Live2DModel::PreloadMotionGroup(const Csm::csmChar* group)
{
    csmInt32 count = _modelSetting->GetMotionCount(group);
    for (csmInt32 i = 0; i < count; i++)
    {
        csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, i);
        csmString motionFile = _modelSetting->GetMotionFileName(group, i);
        std::string motionPath = _modelHomeDir + motionFile.GetRawString();

        Csm::csmSizeInt size = 0;
        Csm::csmByte* buffer = CreateBuffer(motionPath.c_str(), &size);
        if (buffer && size > 0)
        {
            CubismMotion* motion = static_cast<CubismMotion*>(LoadMotion(buffer, size, name.GetRawString(), nullptr, nullptr, _modelSetting, group, i, _motionConsistency));
            if (motion)
            {
                motion->SetEffectIds(_eyeBlinkIds, _lipSyncIds);
                if (_motions[name.GetRawString()] != nullptr)
                {
                    ACubismMotion::Delete(_motions[name.GetRawString()]);
                }
                _motions[name.GetRawString()] = motion;
            }
            DeleteBuffer(buffer);
        }
    }
}

void Live2DModel::SetupTextures()
{
    if (!_modelSetting) return;

    csmInt32 textureCount = _modelSetting->GetTextureCount();
    for (csmInt32 i = 0; i < textureCount; i++)
    {
        const char* texFileName = _modelSetting->GetTextureFileName(i);
        if (!texFileName || std::strlen(texFileName) == 0) continue;

        std::string texPath = _modelHomeDir + texFileName;
        Csm::csmSizeInt size = 0;
        Csm::csmByte* buffer = CreateBuffer(texPath.c_str(), &size);
        if (!buffer || size <= 0)
        {
            Live2DPal::PrintLogLn("[Live2D] Failed to read texture: %s", texPath.c_str());
            continue;
        }

        int width = 0, height = 0, channels = 0;
        unsigned char* imgData = stbi_load_from_memory(buffer, static_cast<int>(size), &width, &height, &channels, STBI_rgb_alpha);
        DeleteBuffer(buffer);

        if (!imgData)
        {
            Live2DPal::PrintLogLn("[Live2D] stbi_load failed: %s", texPath.c_str());
            continue;
        }

        GLuint texId = 0;
        glGenTextures(1, &texId);
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, imgData);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        stbi_image_free(imgData);

        GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->BindTexture(i, texId);
        _textures.push_back(texId);
    }

    GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->IsPremultipliedAlpha(false);
}

void Live2DModel::ReleaseTextures()
{
    for (GLuint texId : _textures)
    {
        if (texId > 0)
        {
            glDeleteTextures(1, &texId);
        }
    }
    _textures.clear();
}

void Live2DModel::ReleaseMotions()
{
    for (auto& pair : _motions)
    {
        if (pair.second)
        {
            ACubismMotion::Delete(pair.second);
        }
    }
    _motions.clear();
}

void Live2DModel::ReleaseExpressions()
{
    for (auto& pair : _expressions)
    {
        if (pair.second)
        {
            ACubismMotion::Delete(pair.second);
        }
    }
    _expressions.clear();
}

void Live2DModel::Update(float deltaTime)
{
    if (!_model) return;

    _userTimeSeconds += deltaTime;
    _motionUpdated = false;

    _model->LoadParameters();

    // Once the queue empties, a one-shot has ended: fall back to the idle rather than freezing on its last
    // pose. Only a real idle group qualifies; looping whichever group happens to come first would not.
    if (_motionManager->IsFinished() && _hasIdleGroup)
    {
        StartMotion(_idleGroupName.c_str(), 0, PriorityIdle);
    }
    if (!_motionManager->IsFinished())
    {
        _motionUpdated = _motionManager->UpdateMotion(_model, deltaTime);
    }

    _model->SaveParameters();
    _opacity = _model->GetModelOpacity();

    _updateScheduler.OnLateUpdate(_model, deltaTime);
    _model->Update();
}

void Live2DModel::Draw(int viewportWidth, int viewportHeight, float scale, float offsetX, float offsetY)
{
    if (!_model) return;

    const float vpWidth = static_cast<float>(viewportWidth > 0 ? viewportWidth : 1);
    const float vpHeight = static_cast<float>(viewportHeight > 0 ? viewportHeight : 1);
    const float viewportAspect = vpWidth / vpHeight;

    CubismMatrix44 projection;
    projection.LoadIdentity();

    const float baseScaleX = viewportAspect >= 1.0f ? 1.0f / viewportAspect : 1.0f;
    const float baseScaleY = viewportAspect >= 1.0f ? 1.0f : viewportAspect;

    projection.Scale(baseScaleX * scale, baseScaleY * scale);
    projection.Translate(offsetX, offsetY);

    if (_modelMatrix != nullptr)
    {
        projection.MultiplyByMatrix(_modelMatrix);
    }

    auto* renderer = GetRenderer<Rendering::CubismRenderer_OpenGLES2>();
    if (renderer)
    {
        renderer->SetMvpMatrix(&projection);
        renderer->DrawModel();
    }
}

void Live2DModel::SetDragging(float x, float y)
{
    if (_dragManager)
    {
        _dragManager->Set(x, y);
    }
}

bool Live2DModel::StartMotion(const char* group, int no, int priority)
{
    if (!_modelSetting || !_motionManager) return false;

    csmInt32 count = _modelSetting->GetMotionCount(group);
    if (no < 0 || no >= count) return false;

    // A forced motion always takes over. Reserving it would fail against a motion of the same priority
    // still playing, and a looping one never finishes, so nothing could be started again.
    if (priority >= PriorityForce)
    {
        _motionManager->SetReservePriority(priority);
    }
    else if (!_motionManager->ReserveMotion(priority))
    {
        return false;
    }

    csmString name = Utils::CubismString::GetFormatedString("%s_%d", group, no);
    auto it = _motions.find(name.GetRawString());
    if (it == _motions.end() || it->second == nullptr)
    {
        const csmString motionFile = _modelSetting->GetMotionFileName(group, no);
        const std::string motionPath = _modelHomeDir + motionFile.GetRawString();
        Csm::csmSizeInt size = 0;
        Csm::csmByte* buffer = CreateBuffer(motionPath.c_str(), &size);
        if (buffer && size > 0)
        {
            CubismMotion* motion = static_cast<CubismMotion*>(LoadMotion(
                buffer,
                size,
                name.GetRawString(),
                nullptr,
                nullptr,
                _modelSetting,
                group,
                no,
                _motionConsistency));
            DeleteBuffer(buffer);
            if (motion)
            {
                motion->SetEffectIds(_eyeBlinkIds, _lipSyncIds);
                _motions[name.GetRawString()] = motion;
                it = _motions.find(name.GetRawString());
            }
        }
    }

    if (it != _motions.end() && it->second != nullptr)
    {
        _motionManager->StartMotionPriority(it->second, false, priority);
        return true;
    }

    // Release the reservation, or it would turn away every later motion of this priority.
    _motionManager->SetReservePriority(0);
    return false;
}

bool Live2DModel::StartRandomMotion(const char* group, int priority)
{
    if (!_modelSetting || !_motionManager) return false;

    csmInt32 count = _modelSetting->GetMotionCount(group);
    if (count <= 0) return false;

    int no = rand() % count;
    return StartMotion(group, no, priority);
}

bool Live2DModel::SetExpression(const char* expressionId)
{
    if (!_expressionManager) return false;

    auto it = _expressions.find(expressionId);
    if (it != _expressions.end() && it->second != nullptr)
    {
        _expressionManager->StartMotion(it->second, false);
        return true;
    }
    return false;
}

bool Live2DModel::HitTest(const char* hitAreaName, float x, float y)
{
    if (!_modelSetting || !_model) return false;

    csmInt32 count = _modelSetting->GetHitAreasCount();
    for (csmInt32 i = 0; i < count; i++)
    {
        if (std::strcmp(_modelSetting->GetHitAreaName(i), hitAreaName) == 0)
        {
            CubismIdHandle drawId = _modelSetting->GetHitAreaId(i);
            return IsHit(drawId, x, y);
        }
    }
    return false;
}

const std::string& Live2DModel::GetModelInfoJson()
{
    std::ostringstream ss;
    ss << "{";
    ss << "\"fileName\":\"" << _modelFileName << "\",";
    ss << "\"homeDir\":\"" << _modelHomeDir << "\",";
    ss << "\"canvasWidth\":" << (_model ? _model->GetCanvasWidth() : 0.0f) << ",";
    ss << "\"canvasHeight\":" << (_model ? _model->GetCanvasHeight() : 0.0f) << ",";
    ss << "\"textureCount\":" << _textures.size() << ",";
    ss << "\"idleGroup\":\"" << _idleGroupName << "\",";

    ss << "\"motionGroups\":[";
    for (size_t i = 0; i < _motionGroups.size(); i++)
    {
        if (i > 0) ss << ",";
        int cnt = _modelSetting ? _modelSetting->GetMotionCount(_motionGroups[i].c_str()) : 0;
        ss << "{\"name\":\"" << _motionGroups[i] << "\",\"count\":" << cnt << "}";
    }
    ss << "],";

    ss << "\"expressions\":[";
    for (size_t i = 0; i < _expressionNames.size(); i++)
    {
        if (i > 0) ss << ",";
        ss << "\"" << _expressionNames[i] << "\"";
    }
    ss << "],";

    ss << "\"hitAreas\":[";
    for (size_t i = 0; i < _hitAreaNames.size(); i++)
    {
        if (i > 0) ss << ",";
        ss << "\"" << _hitAreaNames[i] << "\"";
    }
    ss << "]";

    ss << "}";
    _infoJsonCache = ss.str();
    return _infoJsonCache;
}

void Live2DModel::SetParameterValue(const char* paramId, float value)
{
    if (!_model || !paramId) return;
    const CubismId* id = CubismFramework::GetIdManager()->GetId(paramId);
    if (id)
    {
        _model->SetParameterValue(id, value);
    }
}

void Live2DModel::RefreshModel()
{
    if (_model)
    {
        _model->Update();
    }
}

float Live2DModel::GetParameterValue(const char* paramId)
{
    if (!_model || !paramId) return 0.0f;
    const CubismId* id = CubismFramework::GetIdManager()->GetId(paramId);
    if (id)
    {
        return _model->GetParameterValue(id);
    }
    return 0.0f;
}

int Live2DModel::GetParameterCount() const
{
    return _model ? _model->GetParameterCount() : 0;
}

int Live2DModel::CopyParameterValues(float* outValues, int capacity) const
{
    if (!_model || !outValues || capacity <= 0) return 0;
    const int count = std::min(capacity, _model->GetParameterCount());
    for (int index = 0; index < count; ++index)
    {
        outValues[index] = _model->GetParameterValue(index);
    }
    return count;
}
