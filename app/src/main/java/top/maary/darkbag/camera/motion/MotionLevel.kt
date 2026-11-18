package top.maary.darkbag.camera.motion

enum class MotionLevel {
    STILL,      // Minimal motion: full burst (5~8 frames)
    MODERATE,   // Hand shake / moderate motion: converge to 3 frames (~66ms)
    FAST        // Rapid hand waving / fast pan: fallback to single frame or emergency 3 frames
}
