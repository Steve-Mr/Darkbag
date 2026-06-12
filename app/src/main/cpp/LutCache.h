#ifndef LUT_CACHE_H
#define LUT_CACHE_H

#include <string>
#include <unordered_map>
#include <mutex>
#include "ColorPipe.h"

class LutCache {
public:
    static LutCache& getInstance() {
        static LutCache instance;
        return instance;
    }

    LUT3D getLut(const std::string& path) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = cache_.find(path);
        if (it != cache_.end()) {
            return it->second;
        }

        LUT3D lut = load_lut(path.c_str());
        if (lut.size > 0) {
            cache_[path] = lut;
        }
        return lut;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        cache_.clear();
    }

private:
    LutCache() {}
    ~LutCache() {}

    std::unordered_map<std::string, LUT3D> cache_;
    std::mutex mutex_;
};

#endif // LUT_CACHE_H
