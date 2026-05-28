import re

with open("app/src/main/java/top/maary/darkbag/fragments/PlaygroundViewerFragment.kt", "r") as f:
    content = f.read()

# CaptureMetadata doesn't have ccm or whiteBalance. We will provide defaults since we can't extract them directly here
# Actually, HdrPlusExportWorker gets ccm from intent extras, but for Playground we don't have it directly. We'll use a generic standard CCM or identity. Wait, the original code used FloatArray(9) { if(it%4==0) 1f else 0f } inside ColorProcessor.processRaw, let's see.

# Revert back to the original ccm and whitebalance initialization without reference to captureMetadata.
old_ccm = """                        val ccm = captureMetadata?.ccm ?: floatArrayOf(1.884f, -0.669f, -0.215f, -0.428f, 1.439f, -0.011f, 0.057f, -0.575f, 1.518f)
                        val wb = captureMetadata?.whiteBalance ?: floatArrayOf(1f, 1f, 1f, 1f)"""

new_ccm = """                        val ccm = floatArrayOf(1.884f, -0.669f, -0.215f, -0.428f, 1.439f, -0.011f, 0.057f, -0.575f, 1.518f)
                        val wb = floatArrayOf(1f, 1f, 1f, 1f)"""

content = content.replace(old_ccm, new_ccm)

old_ccm2 = """                    val ccm = captureMetadata?.ccm ?: floatArrayOf(1.884f, -0.669f, -0.215f, -0.428f, 1.439f, -0.011f, 0.057f, -0.575f, 1.518f)
                    val wb = captureMetadata?.whiteBalance ?: floatArrayOf(1f, 1f, 1f, 1f)"""

content = content.replace(old_ccm2, new_ccm2)

with open("app/src/main/java/top/maary/darkbag/fragments/PlaygroundViewerFragment.kt", "w") as f:
    f.write(content)
