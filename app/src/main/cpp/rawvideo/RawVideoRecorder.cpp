#include "RawVideoRecorder.h"
#include "lz4/lz4.h"
#include <android/log.h>
#include <cstring>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#define TAG "RawVideoRecorder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace rawvideo {

namespace {

void applyDpcmEncode(const uint8_t* src, uint8_t* dst, size_t size, size_t stride = 2) {
    if (size == 0) return;
    if (size <= stride) {
        std::memcpy(dst, src, size);
        return;
    }

    // Copy initial stride bytes
    std::memcpy(dst, src, stride);

    size_t i = stride;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    // Fast path: SIMD 16-byte subtraction when stride == 2
    if (stride == 2) {
        for (; i + 16 <= size; i += 16) {
            uint8x16_t cur = vld1q_u8(src + i);
            uint8x16_t prev = vld1q_u8(src + i - 2);
            uint8x16_t diff = vsubq_u8(cur, prev);
            vst1q_u8(dst + i, diff);
        }
    }
#endif
    for (; i < size; ++i) {
        dst[i] = static_cast<uint8_t>(src[i] - src[i - stride]);
    }
}

void applyDpcmDecode(const uint8_t* src, uint8_t* dst, size_t size, size_t stride = 2) {
    if (size == 0) return;
    if (size <= stride) {
        std::memcpy(dst, src, size);
        return;
    }

    std::memcpy(dst, src, stride);
    for (size_t i = stride; i < size; ++i) {
        dst[i] = static_cast<uint8_t>(src[i] + dst[i - stride]);
    }
}

} // namespace

RawVideoRecorder::RawVideoRecorder() = default;

RawVideoRecorder::~RawVideoRecorder() {
    stopRecording();
}

bool RawVideoRecorder::startRecording(const std::string& outputPath, const FileHeader& header) {
    if (isRecording_.load(std::memory_order_relaxed)) {
        LOGW("startRecording called while already recording");
        return false;
    }

    header_ = header;
    if (!writer_.open(outputPath, header_)) {
        LOGE("Failed to initialize writer for path: %s", outputPath.c_str());
        return false;
    }

    // Clear queues
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        std::queue<RawFrameInput> emptyVideo;
        std::swap(videoQueue_, emptyVideo);
        std::queue<AudioPacketInput> emptyAudio;
        std::swap(audioQueue_, emptyAudio);
    }

    stopRequested_.store(false, std::memory_order_relaxed);
    isRecording_.store(true, std::memory_order_relaxed);

    workerThread_ = std::thread(&RawVideoRecorder::workerLoop, this);
    LOGI("RawVideoRecorder started recording to %s", outputPath.c_str());
    return true;
}

bool RawVideoRecorder::pushVideoFrame(const uint8_t* bayerData, size_t dataSize, uint32_t width, uint32_t height,
                                      uint32_t rowStride, uint64_t timestampNs, uint64_t exposureTimeNs, uint32_t iso,
                                      const float* neutralColorPoint, float fNumber, float focalLength) {
    if (!isRecording_.load(std::memory_order_relaxed) || bayerData == nullptr || dataSize == 0) {
        return false;
    }

    std::unique_lock<std::mutex> lock(queueMutex_);
    if (videoQueue_.size() >= MAX_QUEUE_SIZE) {
        LOGW("Video queue overflow (%zu frames), dropping frame to avoid OOM", videoQueue_.size());
        return false;
    }

    RawFrameInput input;
    input.data.assign(bayerData, bayerData + dataSize);
    input.width = width;
    input.height = height;
    input.rowStride = rowStride;
    input.timestampNs = timestampNs;
    input.exposureTimeNs = exposureTimeNs;
    input.iso = iso;
    input.fNumber = fNumber;
    input.focalLength = focalLength;
    if (neutralColorPoint) {
        input.neutralColorPoint[0] = neutralColorPoint[0];
        input.neutralColorPoint[1] = neutralColorPoint[1];
        input.neutralColorPoint[2] = neutralColorPoint[2];
    } else {
        input.neutralColorPoint[0] = 1.0f;
        input.neutralColorPoint[1] = 1.0f;
        input.neutralColorPoint[2] = 1.0f;
    }

    videoQueue_.push(std::move(input));
    lock.unlock();
    queueCv_.notify_one();
    return true;
}

bool RawVideoRecorder::pushAudioPacket(const uint8_t* pcmData, size_t pcmSize, uint64_t timestampNs, uint32_t sampleCount) {
    if (!isRecording_.load(std::memory_order_relaxed) || pcmData == nullptr || pcmSize == 0) {
        return false;
    }

    std::unique_lock<std::mutex> lock(queueMutex_);
    AudioPacketInput input;
    input.pcmData.assign(pcmData, pcmData + pcmSize);
    input.timestampNs = timestampNs;
    input.sampleCount = sampleCount;

    audioQueue_.push(std::move(input));
    lock.unlock();
    queueCv_.notify_one();
    return true;
}

bool RawVideoRecorder::stopRecording() {
    if (!isRecording_.exchange(false)) {
        return true;
    }

    stopRequested_.store(true, std::memory_order_relaxed);
    queueCv_.notify_all();

    if (workerThread_.joinable()) {
        workerThread_.join();
    }

    bool success = writer_.close();
    LOGI("RawVideoRecorder stopped. Success: %d", success);
    return success;
}

void RawVideoRecorder::workerLoop() {
    LOGI("Worker thread started for RawVideoRecorder");

    while (true) {
        RawFrameInput currentVideoFrame;
        bool hasVideo = false;

        AudioPacketInput currentAudioPacket;
        bool hasAudio = false;

        {
            std::unique_lock<std::mutex> lock(queueMutex_);
            queueCv_.wait(lock, [this]() {
                return !videoQueue_.empty() || !audioQueue_.empty() || stopRequested_.load(std::memory_order_relaxed);
            });

            if (!audioQueue_.empty()) {
                currentAudioPacket = std::move(audioQueue_.front());
                audioQueue_.pop();
                hasAudio = true;
            } else if (!videoQueue_.empty()) {
                currentVideoFrame = std::move(videoQueue_.front());
                videoQueue_.pop();
                hasVideo = true;
            } else if (stopRequested_.load(std::memory_order_relaxed)) {
                break;
            }
        }

        if (hasAudio) {
            AudioPacketHeader ah;
            ah.timestampNs = currentAudioPacket.timestampNs;
            ah.sampleCount = currentAudioPacket.sampleCount;
            writer_.writeAudioPacket(ah, currentAudioPacket.pcmData.data(), currentAudioPacket.pcmData.size());
        }

        if (hasVideo) {
            const size_t rawSize = currentVideoFrame.data.size();
            const int maxCompressedSize = LZ4_compressBound(static_cast<int>(rawSize));
            
            if (compressionBuffer_.size() < static_cast<size_t>(maxCompressedSize)) {
                compressionBuffer_.resize(maxCompressedSize);
            }
            if (dpcmBuffer_.size() < rawSize) {
                dpcmBuffer_.resize(rawSize);
            }

            size_t compressedSize = 0;
            if (header_.compressionType == static_cast<uint32_t>(CompressionType::NEON_DPCM_LZ4)) {
                compressedSize = compressFrame(currentVideoFrame.data.data(), rawSize,
                                               compressionBuffer_.data(), compressionBuffer_.size(), true);
            } else {
                compressedSize = 0; // Uncompressed
            }

            VideoFrameHeader vh;
            vh.timestampNs = currentVideoFrame.timestampNs;
            vh.exposureTimeNs = currentVideoFrame.exposureTimeNs;
            vh.iso = currentVideoFrame.iso;
            vh.neutralColorPoint[0] = currentVideoFrame.neutralColorPoint[0];
            vh.neutralColorPoint[1] = currentVideoFrame.neutralColorPoint[1];
            vh.neutralColorPoint[2] = currentVideoFrame.neutralColorPoint[2];
            vh.fNumber = currentVideoFrame.fNumber;
            vh.focalLength = currentVideoFrame.focalLength;
            vh.uncompressedSize = static_cast<uint32_t>(rawSize);

            if (compressedSize > 0 && compressedSize < rawSize) {
                writer_.writeVideoFrame(vh, compressionBuffer_.data(), compressedSize);
            } else {
                // Fallback to uncompressed if compression did not yield savings
                vh.uncompressedSize = static_cast<uint32_t>(rawSize);
                writer_.writeVideoFrame(vh, currentVideoFrame.data.data(), rawSize);
            }
        }
    }

    LOGI("Worker thread exiting for RawVideoRecorder");
}

size_t RawVideoRecorder::compressFrame(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCapacity, bool useDpcm) {
    if (src == nullptr || dst == nullptr || srcSize == 0 || dstCapacity == 0) {
        return 0;
    }

    if (useDpcm) {
        std::vector<uint8_t> dpcm(srcSize);
        applyDpcmEncode(src, dpcm.data(), srcSize, 2);
        int compressedBytes = LZ4_compress_default(
            reinterpret_cast<const char*>(dpcm.data()),
            reinterpret_cast<char*>(dst),
            static_cast<int>(srcSize),
            static_cast<int>(dstCapacity)
        );
        return compressedBytes > 0 ? static_cast<size_t>(compressedBytes) : 0;
    } else {
        int compressedBytes = LZ4_compress_default(
            reinterpret_cast<const char*>(src),
            reinterpret_cast<char*>(dst),
            static_cast<int>(srcSize),
            static_cast<int>(dstCapacity)
        );
        return compressedBytes > 0 ? static_cast<size_t>(compressedBytes) : 0;
    }
}

size_t RawVideoRecorder::decompressFrame(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCapacity, bool useDpcm) {
    if (src == nullptr || dst == nullptr || srcSize == 0 || dstCapacity == 0) {
        return 0;
    }

    if (useDpcm) {
        thread_local std::vector<uint8_t> t_temp;
        if (t_temp.size() < dstCapacity) {
            t_temp.resize(dstCapacity);
        }
        int decompressedBytes = LZ4_decompress_safe(
            reinterpret_cast<const char*>(src),
            reinterpret_cast<char*>(t_temp.data()),
            static_cast<int>(srcSize),
            static_cast<int>(dstCapacity)
        );
        if (decompressedBytes <= 0) {
            return 0;
        }
        applyDpcmDecode(t_temp.data(), dst, static_cast<size_t>(decompressedBytes), 2);
        return static_cast<size_t>(decompressedBytes);
    } else {
        int decompressedBytes = LZ4_decompress_safe(
            reinterpret_cast<const char*>(src),
            reinterpret_cast<char*>(dst),
            static_cast<int>(srcSize),
            static_cast<int>(dstCapacity)
        );
        return decompressedBytes > 0 ? static_cast<size_t>(decompressedBytes) : 0;
    }
}

} // namespace rawvideo
} // namespace darkbag
