#include "RawVideoContainer.h"
#include <android/log.h>
#include <cstring>

#define TAG "RawVideoContainer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace rawvideo {

RawVideoWriter::RawVideoWriter() = default;

RawVideoWriter::~RawVideoWriter() {
    close();
}

bool RawVideoWriter::open(const std::string& filePath, const FileHeader& header) {
    std::lock_guard<std::mutex> lock(writeMutex_);
    if (isOpen_) {
        close();
    }

    filePath_ = filePath;
    header_ = header;
    header_.magic = RAWVID_MAGIC;
    header_.version = RAWVID_VERSION;
    header_.headerSize = sizeof(FileHeader);
    header_.frameCount = 0;
    header_.indexOffset = 0;

    file_.open(filePath_, std::ios::binary | std::ios::trunc | std::ios::out);
    if (!file_.is_open()) {
        LOGE("Failed to open file for writing: %s", filePath_.c_str());
        return false;
    }

    // Write preliminary header (will be updated on close)
    file_.write(reinterpret_cast<const char*>(&header_), sizeof(FileHeader));
    if (!file_.good()) {
        LOGE("Failed to write initial header to: %s", filePath_.c_str());
        file_.close();
        return false;
    }

    frameCount_ = 0;
    frameIndex_.clear();
    audioIndex_.clear();
    isOpen_ = true;
    LOGI("RawVideoWriter opened: %s (%ux%u, %u-bit, %.1f fps)",
         filePath_.c_str(), header_.width, header_.height, header_.bitDepth, header_.fps);
    return true;
}

bool RawVideoWriter::writeVideoFrame(const VideoFrameHeader& frameHeader, const uint8_t* payloadData, size_t payloadSize) {
    std::lock_guard<std::mutex> lock(writeMutex_);
    if (!isOpen_ || !file_.is_open()) {
        return false;
    }

    uint64_t currentOffset = static_cast<uint64_t>(file_.tellp());

    VideoFrameHeader vh = frameHeader;
    vh.chunkType = CHUNK_VIDEO_FRAME;
    vh.frameIndex = frameCount_;
    vh.payloadSize = static_cast<uint32_t>(payloadSize);

    file_.write(reinterpret_cast<const char*>(&vh), sizeof(VideoFrameHeader));
    if (payloadSize > 0 && payloadData != nullptr) {
        file_.write(reinterpret_cast<const char*>(payloadData), payloadSize);
    }

    if (!file_.good()) {
        LOGE("Error writing video frame %u at offset %llu", frameCount_, (unsigned long long)currentOffset);
        return false;
    }

    FrameIndexEntry entry{};
    entry.frameIndex = frameCount_;
    entry.fileOffset = currentOffset;
    entry.timestampNs = frameHeader.timestampNs;
    entry.payloadSize = static_cast<uint32_t>(payloadSize);
    entry.uncompressedSize = frameHeader.uncompressedSize;
    frameIndex_.push_back(entry);

    frameCount_++;
    return true;
}

bool RawVideoWriter::writeAudioPacket(const AudioPacketHeader& audioHeader, const uint8_t* pcmData, size_t pcmSize) {
    std::lock_guard<std::mutex> lock(writeMutex_);
    if (!isOpen_ || !file_.is_open()) {
        return false;
    }

    uint64_t currentOffset = static_cast<uint64_t>(file_.tellp());

    AudioPacketHeader ah = audioHeader;
    ah.chunkType = CHUNK_AUDIO_PACKET;
    ah.payloadSize = static_cast<uint32_t>(pcmSize);

    file_.write(reinterpret_cast<const char*>(&ah), sizeof(AudioPacketHeader));
    if (pcmSize > 0 && pcmData != nullptr) {
        file_.write(reinterpret_cast<const char*>(pcmData), pcmSize);
    }

    if (!file_.good()) {
        LOGE("Error writing audio packet at offset %llu", (unsigned long long)currentOffset);
        return false;
    }

    AudioIndexEntry entry{};
    entry.fileOffset = currentOffset;
    entry.timestampNs = audioHeader.timestampNs;
    entry.sampleCount = audioHeader.sampleCount;
    entry.payloadSize = static_cast<uint32_t>(pcmSize);
    audioIndex_.push_back(entry);

    return true;
}

bool RawVideoWriter::close() {
    std::lock_guard<std::mutex> lock(writeMutex_);
    if (!isOpen_ || !file_.is_open()) {
        isOpen_ = false;
        return true;
    }

    uint64_t indexOffset = static_cast<uint64_t>(file_.tellp());

    // 1. Write Index Table
    uint64_t indexMagic = RAWVID_INDEX_MAGIC;
    file_.write(reinterpret_cast<const char*>(&indexMagic), sizeof(indexMagic));

    uint32_t totalFrames = static_cast<uint32_t>(frameIndex_.size());
    uint32_t totalAudio = static_cast<uint32_t>(audioIndex_.size());
    file_.write(reinterpret_cast<const char*>(&totalFrames), sizeof(totalFrames));
    file_.write(reinterpret_cast<const char*>(&totalAudio), sizeof(totalAudio));

    if (!frameIndex_.empty()) {
        file_.write(reinterpret_cast<const char*>(frameIndex_.data()),
                    frameIndex_.size() * sizeof(FrameIndexEntry));
    }

    if (!audioIndex_.empty()) {
        file_.write(reinterpret_cast<const char*>(audioIndex_.data()),
                    audioIndex_.size() * sizeof(AudioIndexEntry));
    }

    uint64_t footerMagic = RAWVID_FOOTER_MAGIC;
    file_.write(reinterpret_cast<const char*>(&footerMagic), sizeof(footerMagic));

    // 2. Seek back and rewrite updated FileHeader with final frameCount and indexOffset
    if (frameIndex_.size() >= 2) {
        uint64_t startNs = frameIndex_.front().timestampNs;
        uint64_t endNs = frameIndex_.back().timestampNs;
        if (endNs > startNs) {
            uint64_t durationNs = endNs - startNs;
            float measuredFps = static_cast<float>((frameIndex_.size() - 1) * 1000000000.0 / durationNs);
            if (measuredFps > 0.1f && measuredFps < 240.0f) {
                header_.fps = measuredFps;
            }
        }
    }
    header_.frameCount = frameCount_;
    header_.indexOffset = indexOffset;
    file_.seekp(0, std::ios::beg);
    file_.write(reinterpret_cast<const char*>(&header_), sizeof(FileHeader));

    file_.flush();
    file_.close();
    isOpen_ = false;

    LOGI("RawVideoWriter closed successfully. Total frames: %u (fps: %.2f), Audio packets: %u, Index offset: %llu",
         frameCount_, header_.fps, totalAudio, (unsigned long long)indexOffset);
    return true;
}

RawVideoReader::RawVideoReader() = default;

RawVideoReader::~RawVideoReader() {
    close();
}

bool RawVideoReader::openFd(int fd) {
    std::lock_guard<std::mutex> lock(readMutex_);
    close();

    if (fd < 0) {
        LOGE("RawVideoReader: Invalid fd %d", fd);
        return false;
    }

    fd_ = dup(fd);
    if (fd_ < 0) {
        LOGE("RawVideoReader: Failed to dup fd %d: %s", fd, strerror(errno));
        return false;
    }

    // Read FileHeader using pread
    ssize_t bytesRead = pread(fd_, &header_, sizeof(FileHeader), 0);
    if (bytesRead != static_cast<ssize_t>(sizeof(FileHeader)) || header_.magic != RAWVID_MAGIC) {
        LOGE("RawVideoReader: Invalid header or magic in fd %d (got bytes %zd, expected %zu)",
             fd, bytesRead, sizeof(FileHeader));
        ::close(fd_);
        fd_ = -1;
        return false;
    }

    frameIndex_.clear();
    audioIndex_.clear();

    if (header_.indexOffset > 0) {
        uint64_t indexMagic = 0;
        pread(fd_, &indexMagic, sizeof(indexMagic), header_.indexOffset);
        if (indexMagic == RAWVID_INDEX_MAGIC) {
            uint32_t totalFrames = 0;
            uint32_t totalAudio = 0;
            uint64_t offset = header_.indexOffset + sizeof(indexMagic);
            pread(fd_, &totalFrames, sizeof(totalFrames), offset);
            offset += sizeof(totalFrames);
            pread(fd_, &totalAudio, sizeof(totalAudio), offset);
            offset += sizeof(totalAudio);

            if (totalFrames > 0) {
                frameIndex_.resize(totalFrames);
                pread(fd_, frameIndex_.data(), totalFrames * sizeof(FrameIndexEntry), offset);
                offset += totalFrames * sizeof(FrameIndexEntry);
            }
            if (totalAudio > 0) {
                audioIndex_.resize(totalAudio);
                pread(fd_, audioIndex_.data(), totalAudio * sizeof(AudioIndexEntry), offset);
            }
        }
    }

    // Fallback: If index missing, scan chunks sequentially using pread
    if (frameIndex_.empty()) {
        LOGI("RawVideoReader: Index table missing, scanning fd %d linearly...", fd);
        uint64_t curOffset = sizeof(FileHeader);
        uint32_t frameIdx = 0;
        while (true) {
            uint32_t chunkType = 0;
            ssize_t r = pread(fd_, &chunkType, sizeof(chunkType), curOffset);
            if (r != static_cast<ssize_t>(sizeof(chunkType))) break;

            if (chunkType == CHUNK_VIDEO_FRAME) {
                VideoFrameHeader vh;
                ssize_t vhRead = pread(fd_, &vh, sizeof(VideoFrameHeader), curOffset);
                if (vhRead != static_cast<ssize_t>(sizeof(VideoFrameHeader))) break;

                FrameIndexEntry entry{};
                entry.frameIndex = frameIdx++;
                entry.fileOffset = curOffset;
                entry.timestampNs = vh.timestampNs;
                entry.payloadSize = vh.payloadSize;
                entry.uncompressedSize = vh.uncompressedSize;
                frameIndex_.push_back(entry);
                curOffset += sizeof(VideoFrameHeader) + vh.payloadSize;
            } else if (chunkType == CHUNK_AUDIO_PACKET) {
                AudioPacketHeader ah;
                ssize_t ahRead = pread(fd_, &ah, sizeof(AudioPacketHeader), curOffset);
                if (ahRead != static_cast<ssize_t>(sizeof(AudioPacketHeader))) break;

                AudioIndexEntry entry{};
                entry.fileOffset = curOffset;
                entry.timestampNs = ah.timestampNs;
                entry.sampleCount = ah.sampleCount;
                entry.payloadSize = ah.payloadSize;
                audioIndex_.push_back(entry);
                curOffset += sizeof(AudioPacketHeader) + ah.payloadSize;
            } else {
                break;
            }
        }
    }

    isOpen_ = true;
    LOGI("RawVideoReader opened fd %d: %zu frames, %zu audio packets", fd, frameIndex_.size(), audioIndex_.size());
    return true;
}

bool RawVideoReader::open(const std::string& filePath) {
    std::lock_guard<std::mutex> lock(readMutex_);
    close();

    filePath_ = filePath;
    file_.open(filePath_, std::ios::binary | std::ios::in);
    if (!file_.is_open()) {
        LOGE("RawVideoReader: Failed to open %s", filePath_.c_str());
        return false;
    }

    // Read FileHeader
    file_.read(reinterpret_cast<char*>(&header_), sizeof(FileHeader));
    if (!file_.good() || header_.magic != RAWVID_MAGIC) {
        LOGE("RawVideoReader: Invalid magic in %s (got 0x%llx)", filePath_.c_str(), (unsigned long long)header_.magic);
        file_.close();
        return false;
    }

    frameIndex_.clear();
    audioIndex_.clear();

    // Read Index Table if indexOffset is valid
    if (header_.indexOffset > 0) {
        file_.seekg(header_.indexOffset, std::ios::beg);
        uint64_t indexMagic = 0;
        file_.read(reinterpret_cast<char*>(&indexMagic), sizeof(indexMagic));
        if (file_.good() && indexMagic == RAWVID_INDEX_MAGIC) {
            uint32_t totalFrames = 0;
            uint32_t totalAudio = 0;
            file_.read(reinterpret_cast<char*>(&totalFrames), sizeof(totalFrames));
            file_.read(reinterpret_cast<char*>(&totalAudio), sizeof(totalAudio));

            if (totalFrames > 0) {
                frameIndex_.resize(totalFrames);
                file_.read(reinterpret_cast<char*>(frameIndex_.data()), totalFrames * sizeof(FrameIndexEntry));
            }
            if (totalAudio > 0) {
                audioIndex_.resize(totalAudio);
                file_.read(reinterpret_cast<char*>(audioIndex_.data()), totalAudio * sizeof(AudioIndexEntry));
            }
        }
    }

    // Fallback: If index table missing (e.g. abrupt stop), scan chunks sequentially
    if (frameIndex_.empty()) {
        LOGI("RawVideoReader: Index table missing or empty, scanning file linearly...");
        file_.clear();
        file_.seekg(sizeof(FileHeader), std::ios::beg);

        uint32_t frameIdx = 0;
        while (file_.good()) {
            uint64_t chunkOffset = static_cast<uint64_t>(file_.tellg());
            uint32_t chunkType = 0;
            file_.read(reinterpret_cast<char*>(&chunkType), sizeof(chunkType));
            if (!file_.good()) break;

            if (chunkType == CHUNK_VIDEO_FRAME) {
                VideoFrameHeader vh;
                file_.seekg(chunkOffset, std::ios::beg);
                file_.read(reinterpret_cast<char*>(&vh), sizeof(VideoFrameHeader));
                if (!file_.good()) break;

                FrameIndexEntry entry{};
                entry.frameIndex = frameIdx++;
                entry.fileOffset = chunkOffset;
                entry.timestampNs = vh.timestampNs;
                entry.payloadSize = vh.payloadSize;
                entry.uncompressedSize = vh.uncompressedSize;
                frameIndex_.push_back(entry);

                file_.seekg(chunkOffset + sizeof(VideoFrameHeader) + vh.payloadSize, std::ios::beg);
            } else if (chunkType == CHUNK_AUDIO_PACKET) {
                AudioPacketHeader ah;
                file_.seekg(chunkOffset, std::ios::beg);
                file_.read(reinterpret_cast<char*>(&ah), sizeof(AudioPacketHeader));
                if (!file_.good()) break;

                AudioIndexEntry entry{};
                entry.fileOffset = chunkOffset;
                entry.timestampNs = ah.timestampNs;
                entry.sampleCount = ah.sampleCount;
                entry.payloadSize = ah.payloadSize;
                audioIndex_.push_back(entry);

                file_.seekg(chunkOffset + sizeof(AudioPacketHeader) + ah.payloadSize, std::ios::beg);
            } else {
                // Unknown chunk or end reached
                break;
            }
        }
    }

    isOpen_ = true;
    LOGI("RawVideoReader opened %s: %zu frames, %zu audio packets",
         filePath_.c_str(), frameIndex_.size(), audioIndex_.size());
    return true;
}

bool RawVideoReader::readHeader(FileHeader& outHeader) {
    std::lock_guard<std::mutex> lock(readMutex_);
    if (!isOpen_) return false;
    outHeader = header_;
    return true;
}

bool RawVideoReader::readVideoFrame(uint32_t frameIndex, VideoFrameHeader& outHeader, std::vector<uint8_t>& outPayload) {
    std::lock_guard<std::mutex> lock(readMutex_);
    if (!isOpen_ || frameIndex >= frameIndex_.size()) {
        return false;
    }

    const auto& entry = frameIndex_[frameIndex];
    if (fd_ >= 0) {
        ssize_t r = pread(fd_, &outHeader, sizeof(VideoFrameHeader), entry.fileOffset);
        if (r != static_cast<ssize_t>(sizeof(VideoFrameHeader)) || outHeader.chunkType != CHUNK_VIDEO_FRAME) {
            LOGE("RawVideoReader: Corrupt frame header in fd at index %u, offset %llu", frameIndex, (unsigned long long)entry.fileOffset);
            return false;
        }

        outPayload.resize(outHeader.payloadSize);
        if (outHeader.payloadSize > 0) {
            ssize_t pr = pread(fd_, outPayload.data(), outHeader.payloadSize, entry.fileOffset + sizeof(VideoFrameHeader));
            if (pr != static_cast<ssize_t>(outHeader.payloadSize)) {
                LOGE("RawVideoReader: Failed to read video frame payload in fd at index %u", frameIndex);
                return false;
            }
        }
        return true;
    }

    file_.clear();
    file_.seekg(entry.fileOffset, std::ios::beg);

    file_.read(reinterpret_cast<char*>(&outHeader), sizeof(VideoFrameHeader));
    if (!file_.good() || outHeader.chunkType != CHUNK_VIDEO_FRAME) {
        LOGE("RawVideoReader: Corrupt frame header at index %u, offset %llu", frameIndex, (unsigned long long)entry.fileOffset);
        return false;
    }

    outPayload.resize(outHeader.payloadSize);
    if (outHeader.payloadSize > 0) {
        file_.read(reinterpret_cast<char*>(outPayload.data()), outHeader.payloadSize);
    }

    return file_.good();
}

bool RawVideoReader::readAudioPacket(uint32_t packetIndex, AudioPacketHeader& outHeader, std::vector<uint8_t>& outPcm) {
    std::lock_guard<std::mutex> lock(readMutex_);
    if (!isOpen_ || packetIndex >= audioIndex_.size()) {
        return false;
    }

    const auto& entry = audioIndex_[packetIndex];
    if (fd_ >= 0) {
        ssize_t r = pread(fd_, &outHeader, sizeof(AudioPacketHeader), entry.fileOffset);
        if (r != static_cast<ssize_t>(sizeof(AudioPacketHeader)) || outHeader.chunkType != CHUNK_AUDIO_PACKET) {
            LOGE("RawVideoReader: Corrupt audio header in fd at index %u", packetIndex);
            return false;
        }

        outPcm.resize(outHeader.payloadSize);
        if (outHeader.payloadSize > 0) {
            ssize_t pr = pread(fd_, outPcm.data(), outHeader.payloadSize, entry.fileOffset + sizeof(AudioPacketHeader));
            if (pr != static_cast<ssize_t>(outHeader.payloadSize)) {
                return false;
            }
        }
        return true;
    }

    file_.clear();
    file_.seekg(entry.fileOffset, std::ios::beg);

    file_.read(reinterpret_cast<char*>(&outHeader), sizeof(AudioPacketHeader));
    if (!file_.good() || outHeader.chunkType != CHUNK_AUDIO_PACKET) {
        LOGE("RawVideoReader: Corrupt audio header at index %u", packetIndex);
        return false;
    }

    outPcm.resize(outHeader.payloadSize);
    if (outHeader.payloadSize > 0) {
        file_.read(reinterpret_cast<char*>(outPcm.data()), outHeader.payloadSize);
    }

    return file_.good();
}

void RawVideoReader::close() {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
    if (file_.is_open()) {
        file_.close();
    }
    frameIndex_.clear();
    audioIndex_.clear();
    isOpen_ = false;
}

} // namespace rawvideo
} // namespace darkbag
