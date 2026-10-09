#include "HdrPlusStreamingSession.h"
#include "hdrplus_accumulate_step.h"
#include "hdrplus_single_pipeline.h"
#include "ColorPipe.h"
#include "demosaic/RcdDemosaic.h"
#include "gpu/GpuRcdComputeEngine.h"
#include "gpu/GpuAccumulateEngine.h"
#include <android/log.h>
#include <omp.h>
#include <cmath>

extern "C" int halide_set_num_threads(int n);

#define TAG "HdrPlusStreamingSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

HdrPlusStreamingSession::HdrPlusStreamingSession(
    int width,
    int height,
    int orientation,
    int whiteLevel,
    const int* blackLevelPattern,
    const float* lensShadingMap,
    int lensShadingRows,
    int lensShadingCols,
    const float* whiteBalance,
    const float* ccm,
    int cfaPattern,
    const double* noiseProfile,
    int noiseProfileLen,
    int fusionMode,
    float zoomFactor
) : m_width(width),
    m_height(height),
    m_orientation(orientation),
    m_whiteLevel(whiteLevel),
    m_cfaPattern(cfaPattern),
    m_lensShadingRows(lensShadingRows),
    m_lensShadingCols(lensShadingCols),
    m_accumIdx(0),
    m_framesPushed(0),
    m_fusionMode(fusionMode),
    m_zoomFactor(zoomFactor)
{
    m_bl_r  = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[0] : 64));
    m_bl_g0 = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[1] : 64));
    m_bl_g1 = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[2] : 64));
    m_bl_b  = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[3] : 64));

    m_wb_r  = whiteBalance ? whiteBalance[0] : 2.0f;
    m_wb_g0 = whiteBalance ? whiteBalance[1] : 1.0f;
    m_wb_g1 = whiteBalance ? whiteBalance[2] : 1.0f;
    m_wb_b  = whiteBalance ? whiteBalance[3] : 1.5f;
    m_whiteBalance = {m_wb_r, m_wb_g0, m_wb_g1, m_wb_b};

    m_ccm.resize(9);
    if (ccm) {
        std::memcpy(m_ccm.data(), ccm, 9 * sizeof(float));
    } else {
        m_ccm = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    }

    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        size_t lscCount = static_cast<size_t>(4) * lensShadingRows * lensShadingCols;
        m_lensShadingMap.resize(lscCount);
        std::memcpy(m_lensShadingMap.data(), lensShadingMap, lscCount * sizeof(float));
    }

    if (noiseProfile && noiseProfileLen > 0) {
        m_noiseProfile.assign(noiseProfile, noiseProfile + noiseProfileLen);
    }

    // Fixed constant O(1) memory allocation for reference frame
    size_t numPixels = static_cast<size_t>(m_width) * m_height;
    m_refFrame.resize(numPixels);
    // m_accumVal and m_accumWeight are lazily allocated on first CPU frame push to save ~200MB upfront heap

    // Initialize Sabre Super-Resolution Engine only if eligible
    // Architectural Invariant 3 (Strategy isolation per SABRE_FULL_FIDELITY_SPEC.md):
    // - Mode 0 (Auto): zoom >= 1.25x activates Sabre; zoom < 1.25x activates Spatial + RCD (NEVER CHANGED).
    // - Mode 2 (Only Sabre): enables Sabre across ALL zoom levels (including 1x wide angle per Section 2.4 / Phase 3).
    // Invariant 4: GPU Sabre executes headless compute with zero UI thread blocking.
    const bool isSabreEligible = (m_fusionMode == 2) || (m_fusionMode == 0 && m_zoomFactor >= 1.25f);
    if (isSabreEligible) {
        darkbag::sabre::SabreConfig sabreCfg;
        sabreCfg.width = m_width;
        sabreCfg.height = m_height;
        sabreCfg.cfa = static_cast<darkbag::sabre::CfaPattern>(m_cfaPattern);
        sabreCfg.blackLevel[0] = m_bl_r;
        sabreCfg.blackLevel[1] = m_bl_g0;
        sabreCfg.blackLevel[2] = m_bl_g1;
        sabreCfg.blackLevel[3] = m_bl_b;
        sabreCfg.whiteLevel = static_cast<uint16_t>(m_whiteLevel);
        sabreCfg.whiteBalance[0] = m_wb_r;
        sabreCfg.whiteBalance[1] = m_wb_g0;
        sabreCfg.whiteBalance[2] = m_wb_g1;
        sabreCfg.whiteBalance[3] = m_wb_b;
        sabreCfg.zoomFactor = m_zoomFactor;
        if (m_noiseProfile.size() >= 2) {
            sabreCfg.noiseModelS = static_cast<float>(m_noiseProfile[0]);
            sabreCfg.noiseModelO = static_cast<float>(m_noiseProfile[1]);
        }

        if (darkbag::sabre::GpuSabreEngine::isAvailable()) {
            m_gpuSabreEngine = std::make_unique<darkbag::sabre::GpuSabreEngine>(sabreCfg);
            if (!m_gpuSabreEngine->isSessionActive()) {
                LOGW("HdrPlusStreamingSession: GPU Sabre initialization failed, falling back to Spatial + RCD (Invariant 3, SABRE_FULL_FIDELITY_SPEC.md)");
                m_gpuSabreEngine.reset();
            } else {
                LOGD("HdrPlusStreamingSession: GPU Sabre engine initialized (zoom=%.2fx, mode=%d) [SABRE_FULL_FIDELITY_SPEC.md]", m_zoomFactor, m_fusionMode);
            }
        } else {
            m_gpuSabreEngine = nullptr;
        }

        if (!m_gpuSabreEngine) {
            // Smart Fallback (Invariant 3 & SABRE_FULL_FIDELITY_SPEC.md Section 3.2):
            // Avoid slow CPU Sabre (13s blowout on mobile devices). Fall back gracefully to Spatial + RCD (339ms).
            m_sabreEngine = nullptr;
            m_tileAligner = nullptr;
            LOGW("HdrPlusStreamingSession: GPU Sabre unavailable at init, falling back to Spatial + RCD (skipping slow CPU Sabre per SABRE_FULL_FIDELITY_SPEC.md)");
        } else {
            m_sabreEngine = nullptr;
            m_tileAligner = std::make_unique<darkbag::sabre::TileAligner>();
        }
    } else {
        m_gpuSabreEngine = nullptr;
        m_sabreEngine = nullptr;
        m_tileAligner = nullptr;
        LOGD("HdrPlusStreamingSession: Sabre skipped (zoom=%.2fx, mode=%d), saving ~350MB RAM", m_zoomFactor, m_fusionMode);
    }

    // Cap CPU Halide worker threads to prevent core thrashing across dual sessions
    halide_set_num_threads(3);

    // Disable Phase 3C GPU accumulation by default to preserve 60fps UI fluidity and prevent GPU fence timeouts
    m_useGpuAccumulation = false;

    LOGD("HdrPlusStreamingSession initialized: %dx%d, orientation=%d, WL=%d, BL=[%u,%u,%u,%u], fusionMode=%d, zoom=%.2f, gpuAccum=%d",
         m_width, m_height, m_orientation, m_whiteLevel, m_bl_r, m_bl_g0, m_bl_g1, m_bl_b, m_fusionMode, m_zoomFactor, m_useGpuAccumulation);
}

HdrPlusStreamingSession::~HdrPlusStreamingSession() {
    if (m_useGpuAccumulation) {
        darkbag::gpu::GpuAccumulateEngine::instance().endSession();
    }
    m_gpuSabreEngine.reset();
    LOGD("HdrPlusStreamingSession destroyed (total frames pushed: %d)", m_framesPushed);
}

bool HdrPlusStreamingSession::pushFrame(const uint16_t* rawData, size_t numPixels) {
    auto pushStart = std::chrono::high_resolution_clock::now();
    std::lock_guard<std::mutex> lock(m_sessionMutex);
    if (!rawData) {
        LOGE("pushFrame: rawData is null");
        return false;
    }
    const size_t expectedPixels = static_cast<size_t>(m_width) * m_height;
    if (numPixels != expectedPixels) {
        LOGE("pushFrame: numPixels mismatch (got %zu, expected %zu)", numPixels, expectedPixels);
        return false;
    }

    if (m_framesPushed == 0) {
        // Always preserve Frame 0 (Reference Frame) in CPU buffer for fallback and DNG
        std::memcpy(m_refFrame.data(), rawData, numPixels * sizeof(uint16_t));
    }

    if (m_useGpuAccumulation) {
        int64_t gpuPushMs = 0;
        bool gpuOk = darkbag::gpu::GpuAccumulateEngine::instance().pushFrame(rawData, numPixels, &gpuPushMs);
        if (gpuOk) {
            m_framesPushed++;
            m_pushTotalMs += gpuPushMs;
            if (m_pushCount == 0 || gpuPushMs < m_pushMinMs) m_pushMinMs = gpuPushMs;
            if (gpuPushMs > m_pushMaxMs) m_pushMaxMs = gpuPushMs;
            m_pushCount++;
            LOGD("pushFrame (GPU Phase 3C): accumulated frame %d (%lld ms)", m_framesPushed - 1, (long long)gpuPushMs);
            return true;
        } else {
            LOGW("pushFrame (GPU Phase 3C) failed, falling back to CPU Halide accumulation");
            darkbag::gpu::GpuAccumulateEngine::instance().endSession();
            m_useGpuAccumulation = false;
            if (m_accumVal[0].size() != numPixels) {
                m_accumVal[0].resize(numPixels);
                m_accumWeight[0].resize(numPixels);
            }
            #pragma omp parallel for schedule(static)
            for (size_t i = 0; i < numPixels; ++i) {
                m_accumVal[0][i] = static_cast<float>(m_refFrame[i]);
                m_accumWeight[0][i] = 1.0f;
            }
            m_accumIdx = 0;
        }
    }

    if (m_framesPushed == 0) {
        // Frame 0 (Reference Frame)
        std::memcpy(m_refFrame.data(), rawData, numPixels * sizeof(uint16_t));

        const bool isSabreActive = (m_gpuSabreEngine != nullptr) || (m_sabreEngine != nullptr);
        if (!isSabreActive) {
            if (m_accumVal[0].size() != numPixels) {
                m_accumVal[0].resize(numPixels);
                m_accumWeight[0].resize(numPixels);
            }

            #pragma omp parallel for schedule(static)
            for (size_t i = 0; i < numPixels; ++i) {
                m_accumVal[0][i] = static_cast<float>(rawData[i]);
                m_accumWeight[0][i] = 1.0f;
            }
        }

        if (m_tileAligner) {
            m_tileAligner->setReferenceFrame(rawData, m_width, m_height, m_cfaPattern);
        }

        if (m_gpuSabreEngine) {
            bool ok = m_gpuSabreEngine->setReferenceFrame(rawData);
            if (!ok) {
                LOGW("HdrPlusStreamingSession: GPU Sabre setReferenceFrame failed");
            }
        } else if (m_sabreEngine) {
            m_sabreEngine->setReferenceFrame(rawData);
        }

        m_accumIdx = 0;
        m_framesPushed = 1;

        auto pushElapsed = (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - pushStart
        ).count();
        m_pushTotalMs += pushElapsed;
        if (m_pushCount == 0 || pushElapsed < m_pushMinMs) m_pushMinMs = pushElapsed;
        if (pushElapsed > m_pushMaxMs) m_pushMaxMs = pushElapsed;
        m_pushCount++;

        LOGD("pushFrame: registered reference frame 0 (%lld ms)", (long long)pushElapsed);
        return true;
    }

    // Frames 1..N-1 (Alternate Frames)
    const bool isSabreActive = (m_gpuSabreEngine != nullptr) || (m_sabreEngine != nullptr);
    int inIdx = m_accumIdx;
    int outIdx = 1 - m_accumIdx;

    if (!isSabreActive) {
        if (m_accumVal[outIdx].size() != numPixels) {
            m_accumVal[outIdx].resize(numPixels);
            m_accumWeight[outIdx].resize(numPixels);
        }

        Halide::Runtime::Buffer<uint16_t> refBuf(m_refFrame.data(), m_width, m_height);
        Halide::Runtime::Buffer<uint16_t> altBuf(const_cast<uint16_t*>(rawData), m_width, m_height);
        Halide::Runtime::Buffer<float> valIn(m_accumVal[inIdx].data(), m_width, m_height);
        Halide::Runtime::Buffer<float> weightIn(m_accumWeight[inIdx].data(), m_width, m_height);
        Halide::Runtime::Buffer<float> valOut(m_accumVal[outIdx].data(), m_width, m_height);
        Halide::Runtime::Buffer<float> weightOut(m_accumWeight[outIdx].data(), m_width, m_height);

        int res = hdrplus_accumulate_step(refBuf, altBuf, valIn, weightIn, valOut, weightOut);
        if (res != 0) {
            LOGE("hdrplus_accumulate_step failed with error %d at frame %d", res, m_framesPushed);
            return false;
        }
    }

    if (m_tileAligner) {
        bool alignOk = m_tileAligner->alignFrame(rawData, m_flowX, m_flowY, m_flowWidth, m_flowHeight);
        const float* fx = alignOk ? m_flowX.data() : nullptr;
        const float* fy = alignOk ? m_flowY.data() : nullptr;
        int fw = alignOk ? m_flowWidth : 0;
        int fh = alignOk ? m_flowHeight : 0;

        if (m_gpuSabreEngine) {
            m_gpuSabreEngine->accumulateFrame(rawData, fx, fy, fw, fh);
        } else if (m_sabreEngine) {
            m_sabreEngine->accumulateFrame(rawData, fx, fy, fw, fh);
        }
    } else {
        if (m_gpuSabreEngine) {
            m_gpuSabreEngine->accumulateFrame(rawData);
        } else if (m_sabreEngine) {
            m_sabreEngine->accumulateFrame(rawData);
        }
    }

    m_accumIdx = outIdx;
    m_framesPushed++;

    auto pushElapsed = (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - pushStart
    ).count();
    m_pushTotalMs += pushElapsed;
    if (m_pushCount == 0 || pushElapsed < m_pushMinMs) m_pushMinMs = pushElapsed;
    if (pushElapsed > m_pushMaxMs) m_pushMaxMs = pushElapsed;
    m_pushCount++;

    LOGD("pushFrame: accumulated frame %d (%lld ms)", m_framesPushed - 1, (long long)pushElapsed);
    return true;
}

void SharedCaptureResult::releaseGpuResources() {
    if (gpuRgbTexture != 0) {
        darkbag::gpu::GpuRcdComputeEngine::releaseTexture(gpuRgbTexture);
        gpuRgbTexture = 0;
    }
    gpuTexWidth = 0;
    gpuTexHeight = 0;
}

SharedCaptureResult::~SharedCaptureResult() {
    releaseGpuResources();
}

int HdrPlusStreamingSession::finish(
    std::shared_ptr<SharedCaptureResult>& outSharedResult,
    Halide::Runtime::Buffer<uint16_t>& outRgbBuf,
    Halide::Runtime::Buffer<uint16_t>& outBayerBuf
) {
    std::lock_guard<std::mutex> lock(m_sessionMutex);
    if (m_framesPushed <= 0) {
        LOGE("finish called with 0 frames pushed");
        return -1;
    }

    if (!outSharedResult) {
        outSharedResult = std::make_shared<SharedCaptureResult>();
    }

    const size_t numPixels = static_cast<size_t>(m_width) * m_height;
    outSharedResult->bayerBuf.resize(numPixels);
    outSharedResult->noiseProfile = m_noiseProfile;

    // Determine effective fusion mode (Invariant 3 & SABRE_FULL_FIDELITY_SPEC.md Section 3.1):
    // 0: Auto (Spatial+RCD for zoom < 1.25x, Sabre for zoom >= 1.25x) - NEVER CHANGED
    // 1: Spatial + RCD
    // 2: Sabre (Super-Resolution for zoom > 1.05x, Multi-Frame Demosaic for zoom <= 1.05x)
    // 3: Classic Wiener
    int effectiveMode = m_fusionMode;
    if (effectiveMode == 0) {
        effectiveMode = (m_zoomFactor >= 1.25f) ? 2 : 1;
    }

    // Fallback Check 1: If Sabre mode requested but only 1 frame was pushed,
    // multi-frame kernel regression / deghosting cannot operate.
    // Release GPU Sabre engine immediately and gracefully fall back to single-frame RCD (Invariant 1 & 3).
    if (effectiveMode == 2 && m_gpuSabreEngine && m_framesPushed <= 1) {
        LOGW("HdrPlusStreamingSession: Sabre requested (mode=%d, zoom=%.2fx) but only %d frame pushed; gracefully falling back to reference Frame 0 + RCD demosaic (Invariant 1 & 3 per SABRE_FULL_FIDELITY_SPEC.md)",
             effectiveMode, m_zoomFactor, m_framesPushed);
        m_gpuSabreEngine.reset();
        m_tileAligner.reset();
    }

    if (effectiveMode == 2 && (m_gpuSabreEngine || m_sabreEngine) && m_framesPushed > 1) {
        LOGD("HdrPlusStreamingSession: resolving with Sabre (zoom=%.2fx, frames=%d, gpu=%d) [Invariant 3]",
             m_zoomFactor, m_framesPushed, m_gpuSabreEngine ? 1 : 0);
        outSharedResult->rgbBuf.resize(numPixels * 3);
        outSharedResult->bayerBuf.resize(numPixels);
        auto fusionStart = std::chrono::high_resolution_clock::now();
        bool sabreOk = false;

        if (m_gpuSabreEngine) {
            GLuint outTex = 0;
            int64_t gpuComputeMs = 0;
            sabreOk = m_gpuSabreEngine->resolve(
                &outTex,
                outSharedResult->rgbBuf.data(),
                outSharedResult->bayerBuf.data(),
                &gpuComputeMs
            );
            if (sabreOk && outTex != 0) {
                outSharedResult->gpuRgbTexture = m_gpuSabreEngine->transferOutputTexture();
                if (outSharedResult->gpuRgbTexture == 0) {
                    outSharedResult->gpuRgbTexture = outTex;
                }
                outSharedResult->gpuTexWidth = m_width;
                outSharedResult->gpuTexHeight = m_height;
                m_fusionComputeMs = gpuComputeMs;
                LOGD("HdrPlusStreamingSession: GPU Sabre resolve succeeded (%lld ms, tex=%u, zoom=%.2fx)",
                     (long long)m_fusionComputeMs, outSharedResult->gpuRgbTexture, m_zoomFactor);
            } else {
                LOGW("HdrPlusStreamingSession: GPU Sabre resolve failed (sabreOk=%d, outTex=%u), releasing GPU resources and falling back to RCD (Invariant 1 & 3 per SABRE_FULL_FIDELITY_SPEC.md)",
                     sabreOk ? 1 : 0, outTex);
                sabreOk = false;
            }
            m_gpuSabreEngine.reset();
            m_tileAligner.reset();
        } else if (m_sabreEngine) {
            sabreOk = m_sabreEngine->resolve(
                outSharedResult->rgbBuf.data(),
                outSharedResult->bayerBuf.data()
            );
            if (sabreOk) {
                m_fusionComputeMs = (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::high_resolution_clock::now() - fusionStart
                ).count();
                LOGD("HdrPlusStreamingSession: CPU Sabre resolve succeeded (%lld ms)", (long long)m_fusionComputeMs);
            } else {
                LOGW("HdrPlusStreamingSession: CPU Sabre resolve failed, falling back to Spatial + RCD");
            }
        }

        if (sabreOk) {
            if (m_zoomFactor > 1.05f) {
                outSharedResult->isZoomCropped = true;
            } else {
                outSharedResult->isZoomCropped = false;
            }
            outSharedResult->isWhiteBalanceApplied = false;
            outBayerBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->bayerBuf.data(), m_width, m_height);
            outRgbBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->rgbBuf.data(), m_width, m_height, 3);
            return 0;
        }

        // Fallback Check 2: Sabre resolve failed -> clean up GPU resources and reset zoom crop
        LOGW("HdrPlusStreamingSession: Sabre resolve failed or unviable, executing bulletproof fallback to RCD (Invariant 1 & 3 per SABRE_FULL_FIDELITY_SPEC.md)");
        outSharedResult->releaseGpuResources();
        outSharedResult->isZoomCropped = false;
    }

    const bool usedGpuAccumulation = m_useGpuAccumulation;
    bool gpuNormSucceeded = false;
    if (m_useGpuAccumulation && m_framesPushed > 0 && effectiveMode != 2 && effectiveMode != 3) {
        int64_t normMs = 0;
        GLuint normBayerTex = 0;
        // Fast GPU-to-GPU path: pass nullptr to finish() to eliminate 3000ms+ synchronous glReadPixels!
        bool normOk = darkbag::gpu::GpuAccumulateEngine::instance().finish(
            nullptr,
            &normBayerTex,
            &normMs
        );
        m_normalizeMs = normMs;

        if (normOk && normBayerTex != 0) {
            gpuNormSucceeded = true;
            // Pre-populate outSharedResult->bayerBuf with clean reference Frame 0 for fast DNG export and CPU fallback
            std::copy(m_refFrame.begin(), m_refFrame.end(), outSharedResult->bayerBuf.begin());

            uint16_t bl_array[4] = {m_bl_r, m_bl_g0, m_bl_g1, m_bl_b};
            float wb_array[4] = {m_wb_r, m_wb_g0, m_wb_g1, m_wb_b};
            int64_t gpuComputeMs = 0;
            GLuint rgbTex = 0;

            bool demosaicOk = darkbag::gpu::GpuRcdComputeEngine::instance().demosaicFromBayerTexture(
                normBayerTex,
                m_width,
                m_height,
                m_cfaPattern,
                bl_array,
                static_cast<uint16_t>(m_whiteLevel),
                wb_array,
                &rgbTex,
                &gpuComputeMs
            );
            darkbag::gpu::GpuAccumulateEngine::instance().endSession();
            m_useGpuAccumulation = false;

            if (demosaicOk) {
                outSharedResult->gpuRgbTexture = darkbag::gpu::GpuRcdComputeEngine::instance().transferOutputTexture();
                if (outSharedResult->gpuRgbTexture == 0) {
                    outSharedResult->gpuRgbTexture = rgbTex;
                }
                outSharedResult->gpuTexWidth = m_width;
                outSharedResult->gpuTexHeight = m_height;
                outSharedResult->isWhiteBalanceApplied = false;
                m_fusionComputeMs = gpuComputeMs;
                outBayerBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->bayerBuf.data(), m_width, m_height);

                LOGD("HdrPlusStreamingSession (Phase 3C End-to-End GPU): Succeeded in %lld ms (norm=%lld ms, demosaic=%lld ms, texture=%u)",
                     (long long)(m_normalizeMs + m_fusionComputeMs),
                     (long long)m_normalizeMs, (long long)m_fusionComputeMs,
                     outSharedResult->gpuRgbTexture);
                return 0;
            } else {
                LOGW("HdrPlusStreamingSession (Phase 3C): demosaicFromBayerTexture failed, falling back to CPU RCD using Frame 0");
            }
        } else {
            LOGW("HdrPlusStreamingSession (Phase 3C): GpuAccumulateEngine finish failed, falling back to Frame 0");
            darkbag::gpu::GpuAccumulateEngine::instance().endSession();
            m_useGpuAccumulation = false;
        }
    }

    if (gpuNormSucceeded) {
        // GPU normalization already succeeded and populated outSharedResult->bayerBuf.
        // Sync to m_refFrame for downstream consistency; DO NOT overwrite with uninitialized m_accumVal!
        std::copy(outSharedResult->bayerBuf.begin(), outSharedResult->bayerBuf.end(), m_refFrame.begin());
    } else if (m_framesPushed > 0 && m_accumVal[m_accumIdx].size() == numPixels && !usedGpuAccumulation) {
        // Pure CPU accumulation mode: normalize accumulated val / weight into m_refFrame buffer
        auto normStart = std::chrono::high_resolution_clock::now();
        const float* valData = m_accumVal[m_accumIdx].data();
        const float* weightData = m_accumWeight[m_accumIdx].data();

        #pragma omp parallel for schedule(static)
        for (size_t i = 0; i < numPixels; ++i) {
            float norm = valData[i] / std::max(0.001f, weightData[i]) + 0.5f;
            m_refFrame[i] = static_cast<uint16_t>(std::clamp(norm, 0.0f, 65535.0f));
        }
        m_normalizeMs = (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - normStart
        ).count();

        std::copy(m_refFrame.begin(), m_refFrame.end(), outSharedResult->bayerBuf.begin());
    } else {
        // Fallback Check 3 & Fallback Buffer Restoration (Invariant 1):
        // Revert cleanly to retained reference Frame 0 for output Bayer buffer.
        // Guarantees an untainted, valid Bayer frame for downstream RCD demosaic and unified write_dng export.
        LOGW("HdrPlusStreamingSession: Fallback to retained reference Frame 0 for output Bayer buffer (Invariant 1, SABRE_FULL_FIDELITY_SPEC.md)");
        std::copy(m_refFrame.begin(), m_refFrame.end(), outSharedResult->bayerBuf.begin());
    }

    auto fusionStart = std::chrono::high_resolution_clock::now();

    outBayerBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->bayerBuf.data(), m_width, m_height);

    if (effectiveMode == 3) {
        // Classic Wiener mode: requires Halide single pipeline with Malvar 5x5 demosaic
        outSharedResult->rgbBuf.resize(numPixels * 3);
        outRgbBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->rgbBuf.data(), m_width, m_height, 3);

        Halide::Runtime::Buffer<uint16_t> inputBuf(m_refFrame.data(), m_width, m_height, 1);
        Halide::Runtime::Buffer<float> ccmHalideBuf(m_ccm.data(), 3, 3);

        int halideCfa = 1;
        switch (m_cfaPattern) {
            case 0: halideCfa = 1; break; // RGGB
            case 1: halideCfa = 2; break; // GRBG
            case 2: halideCfa = 4; break; // GBRG
            case 3: halideCfa = 3; break; // BGGR
            default: halideCfa = 1; break;
        }

        Halide::Runtime::Buffer<float> lscMapBuf;
        std::vector<float> dummyLsc = {1.0f, 1.0f, 1.0f, 1.0f};
        if (m_lensShadingMap.empty() || m_lensShadingRows <= 0 || m_lensShadingCols <= 0) {
            lscMapBuf = Halide::Runtime::Buffer<float>(dummyLsc.data(), 1, 1, 4);
        } else {
            lscMapBuf = Halide::Runtime::Buffer<float>(const_cast<float*>(m_lensShadingMap.data()), m_lensShadingCols, m_lensShadingRows, 4);
        }

        int halide_res = hdrplus_single_pipeline(
            inputBuf,
            m_bl_r, m_bl_g0, m_bl_g1, m_bl_b,
            static_cast<uint16_t>(m_whiteLevel),
            m_wb_r, m_wb_g0, m_wb_g1, m_wb_b,
            halideCfa,
            ccmHalideBuf,
            lscMapBuf,
            1.0f, 1.0f,
            outRgbBuf,
            outBayerBuf
        );

        if (halide_res != 0) {
            LOGE("hdrplus_single_pipeline failed in streaming finish: %d", halide_res);
            return halide_res;
        }
        outSharedResult->isWhiteBalanceApplied = true;
    } else {
        // High-Fidelity RCD Demosaicing (Spatial + RCD)
        // Perform CPU multithreaded OpenMP+NEON RCD (339ms, zero GPU contention)
        uint16_t bl_array[4] = {m_bl_r, m_bl_g0, m_bl_g1, m_bl_b};
        float wb_array[4] = {m_wb_r, m_wb_g0, m_wb_g1, m_wb_b};

        outSharedResult->rgbBuf.resize(numPixels * 3);
        darkbag::demosaic::rcd_demosaic(
            outSharedResult->bayerBuf.data(),
            m_width,
            m_height,
            m_cfaPattern,
            bl_array,
            static_cast<uint16_t>(m_whiteLevel),
            wb_array,
            outSharedResult->rgbBuf.data()
        );
        outRgbBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->rgbBuf.data(), m_width, m_height, 3);
        outSharedResult->isWhiteBalanceApplied = false;
    }

    m_fusionComputeMs = (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - fusionStart
    ).count();

    LOGD("HdrPlusStreamingSession finish successful: %d frames accumulated into %dx%d result with %s demosaicing (norm=%lld ms, compute=%lld ms)",
         m_framesPushed, m_width, m_height, (effectiveMode == 3) ? "Malvar" : "RCD",
         (long long)m_normalizeMs, (long long)m_fusionComputeMs);
    return 0;
}
