#include "RawVideoRecorder.h"
#include "lz4/lz4.h"
#include <android/log.h>
#include <cstring>
#include <chrono>

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

    // Clear queues and reset frame indices
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        std::queue<RawFrameInput> emptyVideo;
        std::swap(videoQueue_, emptyVideo);
        nextFrameIndex_ = 0;
    }
    {
        std::lock_guard<std::mutex> lock(writerMutex_);
        std::queue<AudioPacketInput> emptyAudio;
        std::swap(audioQueue_, emptyAudio);
        pendingWrites_.clear();
        nextWriteFrameIndex_ = 0;
    }

    stopRequested_.store(false, std::memory_order_relaxed);
    allCompressionFinished_.store(false, std::memory_order_relaxed);
    isRecording_.store(true, std::memory_order_relaxed);

    compressionThreads_.clear();
    compressionThreads_.reserve(NUM_COMPRESSION_THREADS);
    for (size_t i = 0; i < NUM_COMPRESSION_THREADS; ++i) {
        compressionThreads_.emplace_back(&RawVideoRecorder::compressionWorkerLoop, this);
    }
    writerThread_ = std::thread(&RawVideoRecorder::writerLoop, this);

    LOGI("RawVideoRecorder started recording to %s (%zu compression workers + 1 writer thread)",
         outputPath.c_str(), NUM_COMPRESSION_THREADS);
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
    input.frameIndex = nextFrameIndex_++;
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

    std::unique_lock<std::mutex> lock(writerMutex_);
    AudioPacketInput input;
    input.pcmData.assign(pcmData, pcmData + pcmSize);
    input.timestampNs = timestampNs;
    input.sampleCount = sampleCount;

    audioQueue_.push(std::move(input));
    lock.unlock();
    writerCv_.notify_one();
    return true;
}

bool RawVideoRecorder::stopRecording() {
    if (!isRecording_.exchange(false)) {
        return true;
    }

    stopRequested_.store(true, std::memory_order_relaxed);
    queueCv_.notify_all();

    for (auto& t : compressionThreads_) {
        if (t.joinable()) {
            t.join();
        }
    }
    compressionThreads_.clear();

    allCompressionFinished_.store(true, std::memory_order_relaxed);
    writerCv_.notify_all();

    if (writerThread_.joinable()) {
        writerThread_.join();
    }

    bool success = writer_.close();
    LOGI("RawVideoRecorder stopped. Success: %d, written frames: %u", success, writer_.getWrittenFrameCount());
    return success;
}

void RawVideoRecorder::compressionWorkerLoop() {
    LOGI("Compression worker thread started for RawVideoRecorder");
    std::vector<uint8_t> compBuffer;

    while (true) {
        RawFrameInput frame;
        {
            std::unique_lock<std::mutex> lock(queueMutex_);
            queueCv_.wait(lock, [this]() {
                return !videoQueue_.empty() || stopRequested_.load(std::memory_order_relaxed);
            });

            if (!videoQueue_.empty()) {
                frame = std::move(videoQueue_.front());
                videoQueue_.pop();
            } else if (stopRequested_.load(std::memory_order_relaxed)) {
                break;
            } else {
                continue;
            }
        }

        const size_t rawSize = frame.data.size();
        const int maxCompressedSize = LZ4_compressBound(static_cast<int>(rawSize));
        if (compBuffer.size() < static_cast<size_t>(maxCompressedSize)) {
            compBuffer.resize(maxCompressedSize);
        }

        size_t compressedSize = 0;
        if (header_.compressionType == static_cast<uint32_t>(CompressionType::NEON_DPCM_LZ4)) {
            compressedSize = compressFrame(frame.data.data(), rawSize,
                                           compBuffer.data(), compBuffer.size(), true);
        }

        CompressedFrame cf;
        cf.frameIndex = frame.frameIndex;
        cf.header.chunkType = CHUNK_VIDEO_FRAME;
        cf.header.frameIndex = frame.frameIndex;
        cf.header.timestampNs = frame.timestampNs;
        cf.header.exposureTimeNs = frame.exposureTimeNs;
        cf.header.iso = frame.iso;
        cf.header.neutralColorPoint[0] = frame.neutralColorPoint[0];
        cf.header.neutralColorPoint[1] = frame.neutralColorPoint[1];
        cf.header.neutralColorPoint[2] = frame.neutralColorPoint[2];
        cf.header.fNumber = frame.fNumber;
        cf.header.focalLength = frame.focalLength;
        cf.header.uncompressedSize = static_cast<uint32_t>(rawSize);

        if (compressedSize > 0 && compressedSize < rawSize) {
            cf.header.payloadSize = static_cast<uint32_t>(compressedSize);
            cf.data.assign(compBuffer.data(), compBuffer.data() + compressedSize);
        } else {
            cf.header.payloadSize = static_cast<uint32_t>(rawSize);
            cf.data = std::move(frame.data);
        }

        {
            std::lock_guard<std::mutex> lock(writerMutex_);
            pendingWrites_[cf.frameIndex] = std::move(cf);
        }
        writerCv_.notify_one();
    }

    LOGI("Compression worker thread exiting for RawVideoRecorder");
}

void RawVideoRecorder::writerLoop() {
    LOGI("Writer thread started for RawVideoRecorder");

    while (true) {
        CompressedFrame videoToWrite;
        bool hasVideo = false;
        AudioPacketInput audioToWrite;
        bool hasAudio = false;

        {
            std::unique_lock<std::mutex> lock(writerMutex_);
            writerCv_.wait_for(lock, std::chrono::milliseconds(20), [this]() {
                bool hasNextVideo = (pendingWrites_.find(nextWriteFrameIndex_) != pendingWrites_.end());
                bool isDone = allCompressionFinished_.load(std::memory_order_relaxed) &&
                              pendingWrites_.empty() && audioQueue_.empty();
                bool hasAudioPkt = !audioQueue_.empty();
                return hasNextVideo || isDone || (allCompressionFinished_.load(std::memory_order_relaxed) && hasAudioPkt);
            });

            if (allCompressionFinished_.load(std::memory_order_relaxed) &&
                pendingWrites_.empty() && audioQueue_.empty()) {
                break;
            }

            auto it = pendingWrites_.find(nextWriteFrameIndex_);
            if (it != pendingWrites_.end()) {
                uint64_t videoTs = it->second.header.timestampNs;
                if (!audioQueue_.empty() && audioQueue_.front().timestampNs <= videoTs) {
                    audioToWrite = std::move(audioQueue_.front());
                    audioQueue_.pop();
                    hasAudio = true;
                } else {
                    videoToWrite = std::move(it->second);
                    pendingWrites_.erase(it);
                    nextWriteFrameIndex_++;
                    hasVideo = true;
                }
            } else if (allCompressionFinished_.load(std::memory_order_relaxed)) {
                // Compression finished for all incoming frames
                if (!pendingWrites_.empty()) {
                    auto lowest = pendingWrites_.begin();
                    nextWriteFrameIndex_ = lowest->first;
                    videoToWrite = std::move(lowest->second);
                    pendingWrites_.erase(lowest);
                    nextWriteFrameIndex_++;
                    hasVideo = true;
                } else if (!audioQueue_.empty()) {
                    audioToWrite = std::move(audioQueue_.front());
                    audioQueue_.pop();
                    hasAudio = true;
                }
            } else if (!audioQueue_.empty()) {
                // Video frame not ready yet, but audio is accumulating; flush audio
                audioToWrite = std::move(audioQueue_.front());
                audioQueue_.pop();
                hasAudio = true;
            }
        }

        if (hasAudio) {
            AudioPacketHeader ah;
            ah.timestampNs = audioToWrite.timestampNs;
            ah.sampleCount = audioToWrite.sampleCount;
            writer_.writeAudioPacket(ah, audioToWrite.pcmData.data(), audioToWrite.pcmData.size());
        }

        if (hasVideo) {
            writer_.writeVideoFrame(videoToWrite.header, videoToWrite.data.data(), videoToWrite.data.size());
        }
    }

    LOGI("Writer thread exiting for RawVideoRecorder");
}

size_t RawVideoRecorder::compressFrame(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCapacity, bool useDpcm) {
    if (src == nullptr || dst == nullptr || srcSize == 0 || dstCapacity == 0) {
        return 0;
    }

    if (useDpcm) {
        thread_local std::vector<uint8_t> t_dpcm;
        if (t_dpcm.size() < srcSize) {
            t_dpcm.resize(srcSize);
        }
        applyDpcmEncode(src, t_dpcm.data(), srcSize, 2);
        int compressedBytes = LZ4_compress_default(
            reinterpret_cast<const char*>(t_dpcm.data()),
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
