#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <fstream>
#include <mutex>

namespace darkbag {
namespace rawvideo {

#pragma pack(push, 1)

constexpr uint64_t RAWVID_MAGIC = 0x4449565741524244ULL; // "DBRAWVID" in little endian
constexpr uint32_t RAWVID_VERSION = 1;
constexpr uint32_t CHUNK_VIDEO_FRAME = 0x4D524656; // "VFRM"
constexpr uint32_t CHUNK_AUDIO_PACKET = 0x4F445541; // "AUDO"
constexpr uint64_t RAWVID_INDEX_MAGIC = 0x5844495741524244ULL; // "DBRAWIDX"
constexpr uint64_t RAWVID_FOOTER_MAGIC = 0x444E455741524244ULL; // "DBRAWEND"

enum class CfaPattern : uint32_t {
    RGGB = 0,
    GRBG = 1,
    GBRG = 2,
    BGGR = 3
};

enum class CompressionType : uint32_t {
    NONE = 0,
    NEON_DPCM_LZ4 = 1
};

struct FileHeader {
    uint64_t magic = RAWVID_MAGIC;
    uint32_t version = RAWVID_VERSION;
    uint32_t headerSize = sizeof(FileHeader);
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t bitDepth = 10;
    uint32_t cfaPattern = static_cast<uint32_t>(CfaPattern::RGGB);
    float fps = 24.0f;
    uint32_t compressionType = static_cast<uint32_t>(CompressionType::NEON_DPCM_LZ4);
    uint32_t audioSampleRate = 48000;
    uint32_t audioChannels = 1;
    uint32_t audioBitDepth = 16;
    uint32_t whiteLevel = 1023;
    float blackLevel[4] = {64.0f, 64.0f, 64.0f, 64.0f}; // R, Gr, Gb, B
    float colorMatrix1[9] = {1,0,0, 0,1,0, 0,0,1};
    float colorMatrix2[9] = {1,0,0, 0,1,0, 0,0,1};
    float forwardMatrix1[9] = {1,0,0, 0,1,0, 0,0,1};
    float forwardMatrix2[9] = {1,0,0, 0,1,0, 0,0,1};
    float neutralColorPoint[3] = {1.0f, 1.0f, 1.0f};
    char activeLutName[64] = {0};
    char activeLogName[32] = {0};
    uint32_t frameCount = 0;
    uint64_t indexOffset = 0;
    uint32_t orientation = 0; // 0, 90, 180, 270 degrees
    uint32_t calibrationIlluminant1 = 21; // 21 = D65, 17 = Standard Light A
    uint32_t calibrationIlluminant2 = 17;
    float baselineExposure = 0.0f;
    float exposure = 0.0f;
    float contrast = 0.0f;
    float saturation = 0.0f;
    char make[32] = {0};
    char model[64] = {0};
    uint8_t reserved[64] = {0};
};

struct VideoFrameHeader {
    uint32_t chunkType = CHUNK_VIDEO_FRAME;
    uint32_t frameIndex = 0;
    uint64_t timestampNs = 0;
    uint64_t exposureTimeNs = 0;
    uint32_t iso = 100;
    float neutralColorPoint[3] = {1.0f, 1.0f, 1.0f};
    uint32_t uncompressedSize = 0;
    uint32_t payloadSize = 0;
    float fNumber = 0.0f;
    float focalLength = 0.0f;
    uint8_t reserved[16] = {0};
};

struct AudioPacketHeader {
    uint32_t chunkType = CHUNK_AUDIO_PACKET;
    uint64_t timestampNs = 0;
    uint32_t sampleCount = 0;
    uint32_t payloadSize = 0;
};

struct FrameIndexEntry {
    uint32_t frameIndex;
    uint64_t fileOffset;
    uint64_t timestampNs;
    uint32_t payloadSize;
    uint32_t uncompressedSize;
};

struct AudioIndexEntry {
    uint64_t fileOffset;
    uint64_t timestampNs;
    uint32_t sampleCount;
    uint32_t payloadSize;
};

#pragma pack(pop)

class RawVideoWriter {
public:
    RawVideoWriter();
    ~RawVideoWriter();

    bool open(const std::string& filePath, const FileHeader& header);
    bool writeVideoFrame(const VideoFrameHeader& frameHeader, const uint8_t* payloadData, size_t payloadSize);
    bool writeAudioPacket(const AudioPacketHeader& audioHeader, const uint8_t* pcmData, size_t pcmSize);
    bool close();

    uint32_t getWrittenFrameCount() const { return frameCount_; }

private:
    std::string filePath_;
    std::ofstream file_;
    FileHeader header_;
    uint32_t frameCount_ = 0;
    std::vector<FrameIndexEntry> frameIndex_;
    std::vector<AudioIndexEntry> audioIndex_;
    std::mutex writeMutex_;
    bool isOpen_ = false;
};

class RawVideoReader {
public:
    RawVideoReader();
    ~RawVideoReader();

    bool open(const std::string& filePath);
    bool openFd(int fd);
    bool readHeader(FileHeader& outHeader);
    const FileHeader& getHeader() const { return header_; }
    const std::vector<FrameIndexEntry>& getFrameIndex() const { return frameIndex_; }
    const std::vector<AudioIndexEntry>& getAudioIndex() const { return audioIndex_; }

    bool readVideoFrame(uint32_t frameIndex, VideoFrameHeader& outHeader, std::vector<uint8_t>& outPayload);
    bool readAudioPacket(uint32_t packetIndex, AudioPacketHeader& outHeader, std::vector<uint8_t>& outPcm);

    void close();
    bool isOpen() const { return isOpen_; }

private:
    std::string filePath_;
    std::ifstream file_;
    int fd_ = -1;
    FileHeader header_;
    std::vector<FrameIndexEntry> frameIndex_;
    std::vector<AudioIndexEntry> audioIndex_;
    std::mutex readMutex_;
    bool isOpen_ = false;
};

} // namespace rawvideo
} // namespace darkbag
