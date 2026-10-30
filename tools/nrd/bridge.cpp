#include "NRD.h"
#include <cstring>
#include <new>

// Flat C ABI: Java owns Vulkan resources; NRD owns its scheduling and shader descriptions.
// No graphics handles or Java pointers are retained by this bridge.
struct Context {
    nrd::Instance* instance = nullptr;
    const nrd::DispatchDesc* dispatches = nullptr;
    uint32_t count = 0;
};
#define API extern "C" __declspec(dllexport)

API Context* rteNrdCreate() {
    auto* context = new(std::nothrow) Context();
    if (!context) return nullptr;
    nrd::DenoiserDesc denoiser{0, nrd::Denoiser::RELAX_DIFFUSE};
    nrd::InstanceCreationDesc create{};
    create.denoisers = &denoiser; create.denoisersNum = 1;
    if (nrd::CreateInstance(create, context->instance) != nrd::Result::SUCCESS) {
        delete context; return nullptr;
    }
    nrd::RelaxSettings settings{};
    settings.diffuseMaxAccumulatedFrameNum = 16;
    settings.diffuseMaxFastAccumulatedFrameNum = 4;
    settings.atrousIterationNum = 4;
    if (nrd::SetDenoiserSettings(*context->instance, 0, &settings) != nrd::Result::SUCCESS) {
        nrd::DestroyInstance(*context->instance); delete context; return nullptr;
    }
    return context;
}

API void rteNrdDestroy(Context* context) {
    if (!context) return;
    nrd::DestroyInstance(*context->instance); delete context;
}

API void rteNrdInfo(Context* context, uint32_t* result) {
    const auto& instance = *nrd::GetInstanceDesc(*context->instance);
    const auto& library = *nrd::GetLibraryDesc();
    result[0] = instance.pipelinesNum;
    result[1] = instance.permanentPoolSize; result[2] = instance.transientPoolSize;
    result[3] = instance.constantBufferMaxDataSize;
    result[4] = instance.constantBufferAndSamplersSpaceIndex;
    result[5] = instance.resourcesSpaceIndex;
    result[6] = instance.constantBufferRegisterIndex + library.spirvBindingOffsets.constantBufferOffset;
    result[7] = instance.samplersBaseRegisterIndex + library.spirvBindingOffsets.samplerOffset;
    result[8] = instance.resourcesBaseRegisterIndex + library.spirvBindingOffsets.textureOffset;
    result[9] = instance.resourcesBaseRegisterIndex + library.spirvBindingOffsets.storageTextureAndBufferOffset;
    result[10] = static_cast<uint32_t>(library.normalEncoding);
    result[11] = library.versionMajor; result[12] = library.versionMinor; result[13] = library.versionBuild;
}

API const void* rteNrdPipeline(Context* context, uint32_t index, uint32_t* result) {
    const auto& pipeline = nrd::GetInstanceDesc(*context->instance)->pipelines[index];
    result[0] = static_cast<uint32_t>(pipeline.computeShaderSPIRV.size);
    result[1] = 0; result[2] = 0; result[3] = pipeline.hasConstantData;
    for (uint32_t i = 0; i < pipeline.resourceRangesNum; ++i) {
        const auto& range = pipeline.resourceRanges[i];
        result[range.descriptorType == nrd::DescriptorType::TEXTURE ? 1 : 2] += range.descriptorsNum;
    }
    return pipeline.computeShaderSPIRV.bytecode;
}

API uint32_t rteNrdTexture(Context* context, uint32_t permanent, uint32_t index) {
    const auto& instance = *nrd::GetInstanceDesc(*context->instance);
    const auto& texture = permanent ? instance.permanentPool[index] : instance.transientPool[index];
    return static_cast<uint32_t>(texture.format) | (uint32_t(texture.downsampleFactor) << 16);
}

API int rteNrdFrameJittered(Context* context, const float* matrices, uint32_t width, uint32_t height,
                   uint32_t frame, uint32_t reset, float milliseconds) {
    nrd::CommonSettings common{};
    std::memcpy(common.viewToClipMatrix, matrices, 16*sizeof(float));
    std::memcpy(common.viewToClipMatrixPrev, matrices+16, 16*sizeof(float));
    std::memcpy(common.worldToViewMatrix, matrices+32, 16*sizeof(float));
    std::memcpy(common.worldToViewMatrixPrev, matrices+48, 16*sizeof(float));
    common.resourceSize[0] = common.resourceSizePrev[0] = common.rectSize[0] = common.rectSizePrev[0] = uint16_t(width);
    common.resourceSize[1] = common.resourceSizePrev[1] = common.rectSize[1] = common.rectSizePrev[1] = uint16_t(height);
    common.frameIndex = frame;
    common.timeDeltaBetweenFrames = milliseconds;
    std::memcpy(common.cameraJitter, matrices + 64, 2 * sizeof(float));
    std::memcpy(common.cameraJitterPrev, matrices + 66, 2 * sizeof(float));
    common.isMotionVectorInWorldSpace = true;
    common.motionVectorScale[2] = 1.0f;
    common.isHistoryConfidenceAvailable = true;
    common.denoisingRange = 100000;
    common.accumulationMode = reset ? nrd::AccumulationMode::CLEAR_AND_RESTART : nrd::AccumulationMode::CONTINUE;
    auto result = nrd::SetCommonSettings(*context->instance, common);
    if (result != nrd::Result::SUCCESS) return -1;
    nrd::Identifier identifier = 0;
    result = nrd::GetComputeDispatches(*context->instance, &identifier, 1, context->dispatches, context->count);
    return result == nrd::Result::SUCCESS ? int(context->count) : -2;
}

static int semantic(nrd::ResourceType type) {
    using R = nrd::ResourceType;
    switch (type) {
        case R::IN_MV: return 0;
        case R::IN_NORMAL_ROUGHNESS: return 1;
        case R::IN_VIEWZ: return 2;
        case R::IN_DIFF_RADIANCE_HITDIST: return 3;
        case R::IN_DIFF_CONFIDENCE: return 4;
        case R::OUT_DIFF_RADIANCE_HITDIST: return 5;
        case R::TRANSIENT_POOL: return 6;
        case R::PERMANENT_POOL: return 7;
        default: return -1;
    }
}

// Caller supplies 128 integers and InstanceDesc::constantBufferMaxDataSize bytes.
API int rteNrdDispatch(Context* context, uint32_t index, uint32_t* result, void* constants) {
    if (index >= context->count) return -1;
    const auto& dispatch = context->dispatches[index];
    if (dispatch.resourcesNum > 40) return -2;
    result[0] = dispatch.pipelineIndex; result[1] = dispatch.gridWidth; result[2] = dispatch.gridHeight;
    result[3] = dispatch.resourcesNum; result[4] = dispatch.constantBufferDataSize;
    for (uint32_t i = 0; i < dispatch.resourcesNum; ++i) {
        const auto& resource = dispatch.resources[i];
        int type = semantic(resource.type);
        if (type < 0) return -3;
        result[5 + i*3] = uint32_t(type);
        result[6 + i*3] = resource.indexInPool;
        result[7 + i*3] = static_cast<uint32_t>(resource.descriptorType);
    }
    if (dispatch.constantBufferDataSize) std::memcpy(constants,dispatch.constantBufferData,dispatch.constantBufferDataSize);
    return 0;
}
