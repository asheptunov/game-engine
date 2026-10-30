#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define VK_NO_PROTOTYPES
#include <windows.h>
#include <ffx_api/ffx_api_loader.h>
#include <ffx_api/ffx_upscale.h>
#include <ffx_api/vk/ffx_api_vk.h>
#include <new>
#include <cstring>

#define API extern "C" __declspec(dllexport)

// Java retains the device and loaded DLL until all contexts have been destroyed. All instance
// calls are made by the GPU owner, after the previous command buffer has finished executing.
struct Context {
    ffxFunctions functions{};
    ffxContext handle{};
    ffxCreateBackendVKDesc backend{};
    ffxCreateContextDescUpscale create{};
    uint32_t width{}, height{}, outputWidth{}, outputHeight{};
    const char* version{};
};

API Context* rteFsrCreate(void* device, void* physical, void* getDeviceProc,
                         uint32_t width, uint32_t height, uint32_t outputWidth,
                         uint32_t outputHeight, uint32_t* status) {
    auto context = new (std::nothrow) Context;
    if (!context) { *status = FFX_API_RETURN_ERROR_MEMORY; return nullptr; }
    ffxLoadFunctions(&context->functions, GetModuleHandleW(L"amd_fidelityfx_vk.dll"));
    if (!context->functions.CreateContext || !context->functions.DestroyContext
            || !context->functions.Query || !context->functions.Dispatch) {
        *status = FFX_API_RETURN_NO_PROVIDER; delete context; return nullptr;
    }
    context->width = width; context->height = height;
    context->outputWidth = outputWidth; context->outputHeight = outputHeight;
    context->backend.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    context->backend.vkDevice = reinterpret_cast<VkDevice>(device);
    context->backend.vkPhysicalDevice = reinterpret_cast<VkPhysicalDevice>(physical);
    context->backend.vkDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(getDeviceProc);
    context->create.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    context->create.header.pNext = &context->backend.header;
    context->create.flags = FFX_UPSCALE_ENABLE_HIGH_DYNAMIC_RANGE;
    context->create.maxRenderSize = {width, height};
    context->create.maxUpscaleSize = {outputWidth, outputHeight};
    *status = context->functions.CreateContext(&context->handle, &context->create.header, nullptr);
    if (*status != FFX_API_RETURN_OK) { delete context; return nullptr; }
    ffxQueryGetProviderVersion version{};
    version.header.type = FFX_API_QUERY_DESC_TYPE_GET_PROVIDER_VERSION;
    context->functions.Query(&context->handle, &version.header);
    context->version = version.versionName;
    return context;
}

API void rteFsrDestroy(Context* context) {
    if (!context) return;
    context->functions.DestroyContext(&context->handle, nullptr);
    delete context;
}

API const char* rteFsrVersion(Context* context) { return context->version; }

API uint64_t rteFsrMemory(Context* context) {
    FfxApiEffectMemoryUsage usage{};
    ffxQueryDescUpscaleGetGPUMemoryUsage query{};
    query.header.type = FFX_API_QUERY_DESC_TYPE_UPSCALE_GPU_MEMORY_USAGE;
    query.gpuMemoryUsageUpscaler = &usage;
    return context->functions.Query(&context->handle, &query.header) == FFX_API_RETURN_OK
        ? usage.totalUsageInBytes : 0;
}

API uint32_t rteFsrJitter(Context* context, int32_t frame, float* jitter) {
    int32_t phases = 0;
    ffxQueryDescUpscaleGetJitterPhaseCount count{};
    count.header.type = FFX_API_QUERY_DESC_TYPE_UPSCALE_GETJITTERPHASECOUNT;
    count.renderWidth = context->width;
    count.displayWidth = context->outputWidth;
    count.pOutPhaseCount = &phases;
    auto status = context->functions.Query(&context->handle, &count.header);
    if (status != FFX_API_RETURN_OK) return status;
    ffxQueryDescUpscaleGetJitterOffset query{};
    query.header.type = FFX_API_QUERY_DESC_TYPE_UPSCALE_GETJITTEROFFSET;
    query.index = frame; query.phaseCount = phases;
    query.pOutX = jitter; query.pOutY = jitter + 1;
    return context->functions.Query(&context->handle, &query.header);
}

static FfxApiResource resource(uint64_t image, uint32_t format, uint32_t width, uint32_t height) {
    FfxApiResourceDescription description{};
    description.type = FFX_API_RESOURCE_TYPE_TEXTURE2D;
    description.format = format;
    description.width = width; description.height = height;
    description.depth = description.mipCount = 1;
    description.usage = FFX_API_RESOURCE_USAGE_UAV;
    return ffxApiGetResourceVK(reinterpret_cast<void*>(image), description,
                              FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
}

// Images: color, depth, motion, reactive mask, output. Scalars use top-left pixel jitter,
// elapsed milliseconds, near/far distances, vertical FOV in radians and scene-units-to-metres.
API uint32_t rteFsrDispatch(Context* context, void* command, const uint64_t* images,
                           const float* scalars, uint32_t reset) {
    ffxDispatchDescUpscale dispatch{};
    dispatch.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    dispatch.commandList = command;
    dispatch.color = resource(images[0], FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT, context->width, context->height);
    dispatch.depth = resource(images[1], FFX_API_SURFACE_FORMAT_R32_FLOAT, context->width, context->height);
    dispatch.motionVectors = resource(images[2], FFX_API_SURFACE_FORMAT_R16G16_FLOAT, context->width, context->height);
    dispatch.reactive = resource(images[3], FFX_API_SURFACE_FORMAT_R8_UNORM, context->width, context->height);
    dispatch.transparencyAndComposition = dispatch.reactive;
    dispatch.output = resource(images[4], FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT, context->outputWidth, context->outputHeight);
    dispatch.jitterOffset = {scalars[0], scalars[1]};
    dispatch.motionVectorScale = {float(context->width), float(context->height)};
    dispatch.renderSize = {context->width, context->height};
    dispatch.upscaleSize = {context->outputWidth, context->outputHeight};
    dispatch.frameTimeDelta = scalars[2];
    dispatch.preExposure = 1;
    dispatch.reset = reset != 0;
    dispatch.cameraNear = scalars[3]; dispatch.cameraFar = scalars[4];
    dispatch.cameraFovAngleVertical = scalars[5];
    dispatch.viewSpaceToMetersFactor = scalars[6];
    return context->functions.Dispatch(&context->handle, &dispatch.header);
}
