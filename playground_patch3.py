import re

with open("app/src/main/java/top/maary/darkbag/fragments/PlaygroundViewerFragment.kt", "r") as f:
    content = f.read()

# Replace ccm and whitebalance hardcoded with what we can extract from capture metadata

old_ccm = """                        val ccm = FloatArray(9) { if(it%4==0) 1f else 0f }
                        val wb = FloatArray(4) { 1f }"""

new_ccm = """                        val ccm = captureMetadata?.ccm ?: floatArrayOf(1.884f, -0.669f, -0.215f, -0.428f, 1.439f, -0.011f, 0.057f, -0.575f, 1.518f)
                        val wb = captureMetadata?.whiteBalance ?: floatArrayOf(1f, 1f, 1f, 1f)"""

content = content.replace(old_ccm, new_ccm)

# Fix the same for exportToJpg which was replaced earlier
old_ccm2 = """                    val ccm = FloatArray(9) { if(it%4==0) 1f else 0f }
                    val wb = FloatArray(4) { 1f }"""
new_ccm2 = """                    val ccm = captureMetadata?.ccm ?: floatArrayOf(1.884f, -0.669f, -0.215f, -0.428f, 1.439f, -0.011f, 0.057f, -0.575f, 1.518f)
                    val wb = captureMetadata?.whiteBalance ?: floatArrayOf(1f, 1f, 1f, 1f)"""

content = content.replace(old_ccm2, new_ccm2)


with open("app/src/main/java/top/maary/darkbag/fragments/PlaygroundViewerFragment.kt", "w") as f:
    f.write(content)
