#pragma once

#include "RawVideoContainer.h"
#include "BayerProcessor.h"
#include <thread>
#include <atomic>
#include <queue>
#include <map>
#include <vector>
#include <condition_variable>
#include <memory>
#include <functional>

namespace darkbag {
namespace rawvideo {

struct RawFrameInput {
    uint32_t frameIndex = 0;
    std::vector<uint8_t> data;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t rowStride = 0;
    uint64_t timestampNs = 0;
    uint64_t exposureTimeNs = 0;
    uint32_t iso = 100;
    float neutralColorPoint[3] = {1.0f, 1.0f, 1.0f};
    float fNumber = 0.0f;
    float focalLength = 0.0f;
};

struct AudioPacketInput {
    std::vector<uint8_t> pcmData;
    uint64_t timestampNs = 0;
    uint32_t sampleCount = 0;
};

struct CompressedFrame {
    uint32_t frameIndex = 0;
    VideoFrameHeader header{};
    std::vector<uint8_t> data;
};

class RawVideoRecorder {
public:
    RawVideoRecorder();
    ~RawVideoRecorder();

    bool startRecording(const std::string& outputPath, const FileHeader& header, DownsampleMode downsampleMode = DownsampleMode::NONE);
    bool pushVideoFrame(const uint8_t* bayerData, size_t dataSize, uint32_t width, uint32_t height,
                        uint32_t rowStride, uint64_t timestampNs, uint64_t exposureTimeNs, uint32_t iso,
                        const float* neutralColorPoint, float fNumber = 0.0f, float focalLength = 0.0f);
    bool pushAudioPacket(const uint8_t* pcmData, size_t pcmSize, uint64_t timestampNs, uint32_t sampleCount);
    bool stopRecording();

    bool isRecording() const { return isRecording_.load(std::memory_order_relaxed); }
    uint32_t getRecordedFrameCount() const { return writer_.getWrittenFrameCount(); }

    // DPCM + LZ4 Compression and Decompression static utilities
    static size_t compressFrame(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCapacity, bool useDpcm = true);
    static size_t decompressFrame(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCapacity, bool useDpcm = true);

private:
    void compressionWorkerLoop();
    void writerLoop();

    RawVideoWriter writer_;
    FileHeader header_{};
    DownsampleMode downsampleMode_ = DownsampleMode::NONE;
    std::atomic<bool> isRecording_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> allCompressionFinished_{false};

    static constexpr size_t NUM_COMPRESSION_THREADS = 2;
    std::vector<std::thread> compressionThreads_;
    std::thread writerThread_;

    std::mutex queueMutex_;
    std::condition_variable queueCv_;
    std::queue<RawFrameInput> videoQueue_;
    uint32_t nextFrameIndex_ = 0;

    std::mutex writerMutex_;
    std::condition_variable writerCv_;
    std::map<uint32_t, CompressedFrame> pendingWrites_;
    std::queue<AudioPacketInput> audioQueue_;
    uint32_t nextWriteFrameIndex_ = 0;

    static constexpr size_t MAX_QUEUE_SIZE = 20; // Safe queue capacity to absorb camera bursts during compression
};

} // namespace rawvideo
} // namespace darkbag
